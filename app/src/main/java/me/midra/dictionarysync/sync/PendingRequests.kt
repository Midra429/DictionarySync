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
    val outcome: String? = null,
    val error: String? = null,
    val waitReason: String? = null,
    val failureCount: Int = 0,
)

enum class FetchReason(val mask: Int) { MANUAL(1), ALL_SOURCES(2), PERIODIC(4) }

@Dao
interface PendingRequestDao {
    @Query("SELECT * FROM pending_requests WHERE sourceId=:id") suspend fun request(id: String): PendingRequest?
    @Query("SELECT * FROM pending_requests WHERE generation > completedGeneration ORDER BY requestedAt, sourceId")
    suspend fun pending(): List<PendingRequest>
    @Query("SELECT * FROM pending_requests WHERE generation > completedGeneration ORDER BY requestedAt, sourceId")
    fun observePending(): Flow<List<PendingRequest>>
    @Upsert suspend fun put(request: PendingRequest)

    @Query("""UPDATE pending_requests SET notBefore=MAX(notBefore,:deadline), waitReason='SERVER_WAIT'
        WHERE sourceId=:id AND generation>completedGeneration""")
    suspend fun hold(id: String, deadline: Long)

    // Ack only the captured generation; a newer request remains pending, including its wait metadata.
    @Query("""UPDATE pending_requests SET completedGeneration=:generation, completedAt=:now, outcome=:outcome, error=:error,
        waitReason=CASE WHEN generation=:generation THEN NULL ELSE waitReason END
        WHERE sourceId=:id AND configRevision=:revision AND generation>=:generation
        AND completedGeneration<:generation""")
    suspend fun acknowledge(id: String, generation: Long, revision: Long, now: Long, outcome: String, error: String?): Int

    @Query("""UPDATE pending_requests SET notBefore=MAX(notBefore,:notBefore), waitReason=:reason
        WHERE sourceId=:id AND generation=:generation AND configRevision=:revision AND completedGeneration<:generation""")
    suspend fun defer(id: String, generation: Long, revision: Long, notBefore: Long, reason: String): Int
}

/** Room transactions serialize request merges/acks, independently of the long-running execution lock. */
class PendingRequests(private val db: DictionaryDatabase) {
    private val dao get() = db.requests()

    /** The whole batch is durable before the first HTTP call. Repeated taps coalesce by source. */
    suspend fun submit(ids: List<String>, reason: FetchReason, notBefore: Long = 0, now: Long = System.currentTimeMillis()): List<PendingRequest> = db.withTransaction {
        require(notBefore >= 0)
        ids.distinct().map { id ->
            val source = db.library().source(id) ?: throw SyncStopped("SOURCE_MISSING")
            if (source.lifecycle != "ACTIVE") throw SyncStopped("SOURCE_DELETE_PENDING")
            if (!source.enabled) throw SyncStopped("SOURCE_DISABLED")
            val previous = dao.request(id)
            val pending = previous != null && previous.generation > previous.completedGeneration
            PendingRequest(id, Math.incrementExact(previous?.generation ?: 0), source.revision,
                reason.mask or if (pending) previous!!.reasons else 0, now,
                maxOf(source.serverNotBefore, notBefore, if (pending) previous!!.notBefore else 0),
                previous?.completedGeneration ?: 0, previous?.completedAt, previous?.outcome, previous?.error,
                if (source.serverNotBefore > now) "SERVER_WAIT" else if (pending) previous!!.waitReason else null).also { dao.put(it) }
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
            finish(request, "FETCH_FAILED", error, now)
            return@withTransaction false
        }
        val failures = current.failureCount + 1
        val retry = retryable && failures <= HttpRetry.MAX_RETRIES
        dao.put(current.copy(failureCount = failures,
            notBefore = if (retry) maxOf(current.notBefore, HttpRetry.add(now, HttpRetry.backoff(failures))) else current.notBefore,
            waitReason = if (retry) "HTTP_RETRY" else current.waitReason))
        if (!retry) finish(request, "FETCH_FAILED", error, now)
        retry
    }

    suspend fun ready(now: Long = System.currentTimeMillis()) = dao.pending().filter { it.notBefore <= now }

    /** Called under SyncCoordinator before execution. Never runs an obsolete captured request. */
    suspend fun isCurrent(request: PendingRequest, now: Long = System.currentTimeMillis()): Boolean {
        val current = dao.request(request.sourceId) ?: return false
        return current.generation == request.generation && current.configRevision == request.configRevision &&
            current.completedGeneration < request.generation && current.notBefore <= now
    }

    suspend fun finish(request: PendingRequest, outcome: String, error: String? = null, now: Long = System.currentTimeMillis()): Boolean =
        dao.acknowledge(request.sourceId, request.generation, request.configRevision, now, outcome, error) == 1

    suspend fun defer(request: PendingRequest, reason: String, notBefore: Long = request.notBefore): Boolean =
        dao.defer(request.sourceId, request.generation, request.configRevision, notBefore, reason) == 1
}
