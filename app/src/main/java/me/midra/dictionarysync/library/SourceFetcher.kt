package me.midra.dictionarysync.library

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.FetchState
import me.midra.dictionarysync.data.local.Preferences
import me.midra.dictionarysync.data.local.SnapshotEntity
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.local.SyncState
import me.midra.dictionarysync.data.parser.DictionaryParser
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.Download
import me.midra.dictionarysync.data.source.DictionaryDownload
import me.midra.dictionarysync.data.source.HttpRetry
import me.midra.dictionarysync.data.source.HttpValidators
import me.midra.dictionarysync.data.source.ValidatorHeaders
import me.midra.dictionarysync.sync.FetchOutcome
import me.midra.dictionarysync.sync.PendingRequest
import me.midra.dictionarysync.sync.PendingRequests
import me.midra.dictionarysync.sync.SyncCoordinator
import me.midra.dictionarysync.sync.WaitReason

/**
 * Executes durable fetch requests of registered sources. A failure keeps the previous settings, words, count and
 * update time; only a fully parsed generation replaces them. Each request is acknowledged atomically with its result.
 */
class SourceFetcher(
    private val context: Context,
    private val db: DictionaryDatabase,
    private val preferences: Preferences,
    private val requests: PendingRequests,
    private val snapshots: SnapshotStore,
    private val download: DictionaryDownload,
    private val now: () -> Long,
) {
    private val dao = db.library()

    /** A fetch still marked running was cut off with its process. */
    suspend fun markInterrupted() = SyncCoordinator.locked(context) {
        dao.sources().filter { it.state == FetchState.FETCHING }.forEach { dao.updateSource(it.copy(state = FetchState.INTERRUPTED, error = null)) }
    }

    /** Executes [request] if it is still current. The request is already durable; this path owns the long-running lock. */
    suspend fun execute(request: PendingRequest): Unit = withContext(Dispatchers.IO) {
        SyncCoordinator.locked(context) {
            if (!requests.isCurrent(request, now())) return@locked
            val source = dao.source(request.sourceId)
            if (source == null || !source.syncable || source.revision != request.configRevision) {
                requests.finish(request, when {
                    source?.listed != true -> FetchOutcome.SOURCE_REMOVED
                    !source.enabled -> FetchOutcome.SOURCE_DISABLED
                    else -> FetchOutcome.SETTINGS_CHANGED
                })
                return@locked
            }
            if (source.serverNotBefore > now()) {
                db.withTransaction {
                    requests.defer(request, WaitReason.SERVER_WAIT, source.serverNotBefore)
                    dao.updateSource(source.copy(state = FetchState.FETCH_RETRY, error = WaitReason.SERVER_WAIT.name))
                }
                return@locked
            }
            dao.updateSource(source.copy(state = FetchState.FETCHING, error = null))
            val cached = snapshots.validatedLatest(source)
            val wifiOnly = preferences.wifiOnly.first() // Preference/storage errors must remain unacknowledged.
            val candidate = try {
                downloadCandidate(source, cached, wifiOnly).also { currentCoroutineContext().ensureActive() }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (error is CancellationException) throw error // An unacknowledged request resumes after a restart.
                failed(source, request, error)
                return@locked
            }
            when {
                preferences.wifiOnly.first() != wifiOnly -> db.withTransaction {
                    dao.updateSource(source.copy(state = FetchState.NETWORK_WAIT, error = WaitReason.POLICY_CHANGED.name))
                    requests.defer(request, WaitReason.POLICY_CHANGED)
                }
                candidate == null -> db.withTransaction {
                    check(cached != null && snapshots.validatedLatest(source)?.id == cached.id) { "CACHE_LOST" }
                    dao.updateSource(source.copy(state = FetchState.FETCHED, error = null, fetchedAt = now(), syncState = SyncState.UPDATE_PENDING))
                    requests.finish(request, FetchOutcome.NOT_MODIFIED)
                }
                else -> store(source, request, candidate)
            }
            Unit
        }
    }

    /** Downloads and parses [source]; null for a 304 Not Modified validated against [cached]. */
    private fun downloadCandidate(source: SourceEntity, cached: SnapshotEntity?, wifiOnly: Boolean): SnapshotStore.Candidate? {
        val validators = cached?.let { HttpValidators(it.finalUrl, ValidatorHeaders.etag(it.etag), ValidatorHeaders.lastModified(it.lastModified)) }
            ?.takeIf { it.usable }
        val result: Download = try { download.get(source.url, wifiOnly, validators) } catch (e: DictionaryProblem) {
            if (e.code != "INVALID_304") throw e
            download.get(source.url, wifiOnly, null) // One bounded unconditional recovery; a 304 body is never parsed.
        }
        if (result.notModified) {
            if (cached == null || validators == null || result.finalUrl != cached.finalUrl) throw DictionaryProblem("UNEXPECTED_304")
            return null
        }
        val parsed = DictionaryParser.parse(result.bytes, source.encoding, source.format, result.contentType)
        return SnapshotStore.Candidate.of(result, parsed, source.locale)
    }

    /** Waits for the network, or charges the retry budget; the previous generation stays either way. */
    private suspend fun failed(source: SourceEntity, request: PendingRequest, error: Exception) {
        val problem = error as? DictionaryProblem
        val reason = problem?.let { it.code + (it.line?.let { n -> " (行 $n)" } ?: "") } ?: error.javaClass.simpleName
        val wait = WaitReason.of(problem?.code)?.takeIf { it.network }
        db.withTransaction {
            if (wait != null) {
                dao.updateSource(source.copy(state = FetchState.NETWORK_WAIT, error = reason))
                requests.defer(request, wait)
            } else {
                val retry = requests.failed(request, reason, HttpRetry.retryable(error), HttpRetry.retryAfter(problem?.retryAfter, now()), now())
                val current = dao.source(source.id) ?: throw DictionaryProblem("SOURCE_MISSING")
                dao.updateSource(current.copy(state = if (retry) FetchState.FETCH_RETRY else FetchState.FETCH_FAILED, error = reason))
            }
        }
    }

    /** Commits a new generation with its request's acknowledgement. Storage errors propagate unacknowledged. */
    private suspend fun store(source: SourceEntity, request: PendingRequest, candidate: SnapshotStore.Candidate) {
        val previous = (source.activeSnapshot ?: source.latestSnapshot)?.let { dao.snapshot(it) }
        val fetchedAt = now()
        db.withTransaction {
            val current = dao.source(source.id)
            if (current?.revision != request.configRevision || !current.listed) throw DictionaryProblem("SETTINGS_CHANGED")
            val snapshot = snapshots.add(source, candidate, fetchedAt)
            dao.updateSource(source.copy(latestSnapshot = snapshot.id, previousSnapshot = source.latestSnapshot,
                state = FetchState.FETCHED, error = null, fetchedAt = fetchedAt, syncState = SyncState.UPDATE_PENDING,
                changedAt = if (previous?.contentHash != candidate.contentHash) fetchedAt else source.changedAt))
            snapshots.prune(source, keep = setOf(snapshot.id, source.latestSnapshot, source.activeSnapshot))
            requests.finish(request, FetchOutcome.FETCHED)
        }
    }
}
