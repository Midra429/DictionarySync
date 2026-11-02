package me.midra.dictionarysync.sync

import androidx.room.*
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.source.HttpRetry

/** Fetch requests only. Completion means handled, not Provider synchronization or even fetch success.
 * No cascading FK: a locally removed source must not erase the last request result/generation. */
@Entity(tableName = "pending_requests")
data class PendingRequest(
    @PrimaryKey val sourceId: String,
    val generation: Long,
    val configRevision: Long,
    val reasons: Int,
    val requestedAt: Long,
    val notBefore: Long,
    val completedGeneration: Long = 0,
    val completedAt: Long? = null,
    val outcome: FetchOutcome? = null,
    val error: String? = null,
    val waitReason: WaitReason? = null,
    val failureCount: Int = 0,
) {
    val pending get() = generation > completedGeneration
}

/** Why a fetch was requested; [mask] bits are merged while a request is pending. */
enum class FetchReason(val mask: Int) { MANUAL(1), ALL_SOURCES(2), PERIODIC(4) }

/** How a request was handled. Stored by name. Not the outcome of the Provider sync. */
enum class FetchOutcome { FETCHED, NOT_MODIFIED, FETCH_FAILED, SOURCE_REMOVED, SOURCE_DISABLED, SETTINGS_CHANGED }

/** Why a pending request waits. Stored by name. */
enum class WaitReason {
    SERVER_WAIT, HTTP_RETRY,
    /** Wi-Fi only is on and no accepted Wi-Fi is available. */
    WIFI_WAIT,
    NETWORK_WAIT,
    /** The Wi-Fi only setting changed during the fetch. */
    POLICY_CHANGED;

    /** Waits for the device's connectivity rather than for a time; these never consume the retry budget. */
    val network get() = this == WIFI_WAIT || this == NETWORK_WAIT || this == POLICY_CHANGED

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.name == code }
    }
}

@Dao
interface PendingRequestDao {
    @Query("SELECT * FROM pending_requests WHERE sourceId=:id") suspend fun request(id: String): PendingRequest?
    @Query("SELECT * FROM pending_requests WHERE generation > completedGeneration ORDER BY requestedAt, sourceId")
    suspend fun pending(): List<PendingRequest>
    @Query("SELECT * FROM pending_requests WHERE generation > completedGeneration ORDER BY requestedAt, sourceId")
    fun observePending(): Flow<List<PendingRequest>>
    @Upsert suspend fun put(request: PendingRequest)

    // The literal is WaitReason.SERVER_WAIT.
    @Query("""UPDATE pending_requests SET notBefore=MAX(notBefore,:deadline), waitReason='SERVER_WAIT'
        WHERE sourceId=:id AND generation>completedGeneration""")
    suspend fun hold(id: String, deadline: Long)

    // Ack only the captured generation; a newer request remains pending, including its wait metadata.
    @Query("""UPDATE pending_requests SET completedGeneration=:generation, completedAt=:now, outcome=:outcome, error=:error,
        waitReason=CASE WHEN generation=:generation THEN NULL ELSE waitReason END
        WHERE sourceId=:id AND configRevision=:revision AND generation>=:generation
        AND completedGeneration<:generation""")
    suspend fun acknowledge(id: String, generation: Long, revision: Long, now: Long, outcome: FetchOutcome, error: String?): Int

    @Query("""UPDATE pending_requests SET notBefore=MAX(notBefore,:notBefore), waitReason=:reason
        WHERE sourceId=:id AND generation=:generation AND configRevision=:revision AND completedGeneration<:generation""")
    suspend fun defer(id: String, generation: Long, revision: Long, notBefore: Long, reason: WaitReason): Int
}

/** Room transactions serialize request merges/acks, independently of the long-running execution lock. */
class PendingRequests(private val db: DictionaryDatabase) {
    private val dao get() = db.requests()

    /** The whole batch is durable before the first HTTP call. Repeated taps coalesce by source. */
    suspend fun submit(ids: List<String>, reason: FetchReason, notBefore: Long = 0, now: Long = System.currentTimeMillis()): List<PendingRequest> = db.withTransaction {
        require(notBefore >= 0)
        ids.distinct().map { id ->
            val source = db.library().source(id) ?: throw SyncStopped("SOURCE_MISSING")
            if (!source.listed) throw SyncStopped("SOURCE_DELETE_PENDING")
            if (!source.enabled) throw SyncStopped("SOURCE_DISABLED")
            val previous = dao.request(id)
            val pending = previous?.pending == true
            PendingRequest(id, Math.incrementExact(previous?.generation ?: 0), source.revision,
                reason.mask or if (pending) previous!!.reasons else 0, now,
                maxOf(source.serverNotBefore, notBefore, if (pending) previous!!.notBefore else 0),
                previous?.completedGeneration ?: 0, previous?.completedAt, previous?.outcome, previous?.error,
                if (source.serverNotBefore > now) WaitReason.SERVER_WAIT else if (pending) previous!!.waitReason else null).also { dao.put(it) }
        }
    }

    /** A server hold also reaches a newer in-flight submission; stale failures never charge its budget. */
    suspend fun failed(request: PendingRequest, error: String, retryable: Boolean, serverDeadline: Long?, now: Long): Boolean = db.withTransaction {
        if (serverDeadline != null) {
            db.library().source(request.sourceId)?.let { source ->
                db.library().updateSource(source.copy(serverNotBefore = maxOf(source.serverNotBefore, serverDeadline)))
            }
            dao.hold(request.sourceId, serverDeadline)
        }
        val current = dao.request(request.sourceId) ?: return@withTransaction false
        if (current.generation != request.generation || current.configRevision != request.configRevision || current.completedGeneration >= request.generation) {
            finish(request, FetchOutcome.FETCH_FAILED, error, now)
            return@withTransaction false
        }
        val failures = current.failureCount + 1
        val retry = retryable && failures <= HttpRetry.MAX_RETRIES
        dao.put(current.copy(failureCount = failures,
            notBefore = if (retry) maxOf(current.notBefore, HttpRetry.add(now, HttpRetry.backoff(failures))) else current.notBefore,
            waitReason = if (retry) WaitReason.HTTP_RETRY else current.waitReason))
        if (!retry) finish(request, FetchOutcome.FETCH_FAILED, error, now)
        retry
    }

    suspend fun pending() = dao.pending()
    suspend fun request(sourceId: String) = dao.request(sourceId)
    suspend fun ready(now: Long = System.currentTimeMillis()) = dao.pending().filter { it.notBefore <= now }
    /** How long until the next pending request is due; null when nothing is pending. */
    suspend fun nextDelay(now: Long) = dao.pending().minOfOrNull { (it.notBefore - now).coerceAtLeast(0) }

    /** Called under SyncCoordinator before execution. Never runs an obsolete captured request. */
    suspend fun isCurrent(request: PendingRequest, now: Long = System.currentTimeMillis()): Boolean {
        val current = dao.request(request.sourceId) ?: return false
        return current.generation == request.generation && current.configRevision == request.configRevision &&
            current.completedGeneration < request.generation && current.notBefore <= now
    }

    suspend fun finish(request: PendingRequest, outcome: FetchOutcome, error: String? = null, now: Long = System.currentTimeMillis()): Boolean =
        dao.acknowledge(request.sourceId, request.generation, request.configRevision, now, outcome, error) == 1

    suspend fun defer(request: PendingRequest, reason: WaitReason, notBefore: Long = request.notBefore): Boolean =
        dao.defer(request.sourceId, request.generation, request.configRevision, notBefore, reason) == 1
}
