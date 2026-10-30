package me.midra.dictionarysync.sync

import android.content.Context
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.library.LibraryRepository

interface FetchDispatch {
    suspend fun enqueue()
    /** A fetch the user just asked for. */
    suspend fun enqueueNow() = enqueue()
    /** Shared-dictionary writes the user just asked for (no network needed). */
    suspend fun applyNow() = enqueueNow()
    suspend fun wakeAfter(delayMillis: Long)
    suspend fun cancelWake()
    suspend fun sources(sources: List<me.midra.dictionarysync.data.local.SourceEntity>)
}

/** Durable fetch chain; successful generations are applied through the existing sync engine. */
class FetchWork(context: Context, private val manager: WorkManager = WorkManager.getInstance(context)) : FetchDispatch {
    override suspend fun enqueue() {
        manager.enqueueUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request()).await()
    }
    override suspend fun enqueueNow() {
        manager.enqueueUniqueWork(CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request(expedited = true)).await()
    }
    override suspend fun applyNow() {
        manager.enqueueUniqueWork(APPLY, ExistingWorkPolicy.APPEND_OR_REPLACE, applyRequest()).await()
    }
    override suspend fun wakeAfter(delayMillis: Long) {
        // A separate timer must never block a newly requested, earlier fetch behind its delay.
        val timer = OneTimeWorkRequestBuilder<FetchWakeWorker>()
            .setInitialDelay(delayMillis.coerceIn(0, DAY_MILLIS), TimeUnit.MILLISECONDS).build()
        manager.enqueueUniqueWork(WAKE, ExistingWorkPolicy.REPLACE, timer).await()
    }
    override suspend fun cancelWake() { manager.cancelUniqueWork(WAKE).await() }
    override suspend fun sources(sources: List<me.midra.dictionarysync.data.local.SourceEntity>) {
        for (source in sources) {
            val name = periodicName(source.id)
            if (source.lifecycle == "ACTIVE" && source.enabled) {
                manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest(source.id, source.syncIntervalHours)).await()
            } else manager.cancelUniqueWork(name).await()
        }
    }
    companion object {
        const val CHAIN = "dictionary-fetch"
        const val WAKE = "dictionary-fetch-wake"
        const val PERIODIC = "dictionary-periodic"
        const val APPLY = "dictionary-apply"
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        fun periodicName(id: String) = "$PERIODIC:$id"
        fun periodicRequest(id: String? = null, hours: Int = 24) = PeriodicWorkRequestBuilder<PeriodicFetchWorker>(hours.toLong(), TimeUnit.HOURS)
            .setInputData(workDataOf("sourceId" to id))
            .setInitialDelay(hours.toLong(), TimeUnit.HOURS).build()
        /** Provider writes need no network; expedited on Android 12+ because the user is waiting for them. */
        fun applyRequest(sdk: Int = Build.VERSION.SDK_INT) = OneTimeWorkRequestBuilder<ApplyWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply { if (sdk >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        fun request(expedited: Boolean = false, sdk: Int = Build.VERSION.SDK_INT) = OneTimeWorkRequestBuilder<FetchWorker>()
            // Coarse wake-up constraint. The existing route still proves and pins actual Wi-Fi.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Before Android 12 expedited work needs a foreground notification; there it stays a regular request.
            .apply { if (expedited && sdk >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
    }
}

/** Saves first, then enqueues. Startup repairs the intentionally non-atomic Room/enqueue boundary. */
class FetchRequests(private val library: LibraryRepository, private val dispatch: FetchDispatch) {
    suspend fun one(id: String) {
        library.requests.submit(listOf(id), FetchReason.MANUAL)
        dispatch.enqueueNow()
    }
    suspend fun all() {
        library.requestAllFetches()
        repair(userRequested = true)
    }
    suspend fun repair(userRequested: Boolean = false) {
        dispatch.sources(library.schedulingSources())
        if (library.database.requests().pending().isNotEmpty()) { if (userRequested) dispatch.enqueueNow() else dispatch.enqueue() }
        // Pending shared-dictionary writes go to the apply chain, which does not wait for a network.
        if (library.dao.sources().any { it.syncState in LibraryRepository.BACKGROUND_SYNC_STATES }) dispatch.applyNow()
    }
}

/** One source per Worker bounds execution and yields between sources. State lives entirely in Room. */
class FetchRunner(private val library: LibraryRepository, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun runOne(events: SyncEvents = SyncEvents.None): Long? {
        library.reconcileInterruptedFetch()
        library.requests.ready(now()).firstOrNull()?.let { request ->
            library.dao.source(request.sourceId)?.let(events::fetching)
            library.executeFetch(request)
            val current = library.database.requests().request(request.sourceId)
            // Only this run can acknowledge its generation as failed; retries still pending are not reported yet.
            if (current != null && current.completedGeneration >= request.generation && current.outcome == "FETCH_FAILED")
                library.dao.source(request.sourceId)?.let { events.fetchFailed(it, current.error ?: "FETCH_FAILED") }
            if (current?.generation == request.generation && current.completedGeneration < request.generation && current.waitReason in setOf("WIFI_WAIT", "NETWORK_WAIT", "POLICY_CHANGED")) {
                // Conditions do not consume the generation or failure-attempt budget. No tight loop.
                library.requests.defer(request, current.waitReason!!, Math.addExact(now(), WAIT_MILLIS))
            }
        }
        library.syncPending(1, events)
        if (library.dao.sources().any { it.syncState in LibraryRepository.BACKGROUND_SYNC_STATES }) return 0L
        return library.database.requests().pending().minOfOrNull { (it.notBefore - now()).coerceAtLeast(0) }
    }
    companion object { const val WAIT_MILLIS = 15 * 60 * 1000L }
}

class FetchWorker internal constructor(context: Context, params: WorkerParameters,
    private val runner: FetchRunner, private val dispatch: FetchDispatch, private val events: SyncEvents = SyncEvents.None) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params,
        FetchRunner(LibraryRepository(context, DictionaryDatabase.get(context))), FetchWork(context), SyncNotifier(context))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // The progress notification stays up while the next pass is queued right behind this one.
        var continues = false
        try {
            val delay = runner.runOne(events)
            continues = delay == 0L
            when (delay) {
                null -> dispatch.cancelWake()
                0L -> dispatch.enqueue()
                else -> dispatch.wakeAfter(delay)
            }
            // This acknowledges a scheduling pass, not a successful fetch or Provider apply.
            Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Storage/enqueue errors never acknowledge business requests. Startup can repair even
            // after bounded infrastructure retries are exhausted.
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } finally {
            if (!continues) events.idle()
        }
    }
}

/** Applies desired shared-dictionary states (enable, disable, delete, new generations) through the sync engine. */
class ApplyRunner(private val library: LibraryRepository) {
    /** Returns true when sources are still due after a pass that changed something, so another pass is useful. */
    suspend fun run(events: SyncEvents = SyncEvents.None): Boolean {
        fun due(sources: List<me.midra.dictionarysync.data.local.SourceEntity>) =
            sources.filter { it.syncState in LibraryRepository.BACKGROUND_SYNC_STATES }.map { it.id to it.syncState }
        val before = due(library.dao.sources())
        library.syncPending(events = events)
        val after = due(library.dao.sources())
        return after.isNotEmpty() && after != before
    }
}

class ApplyWorker internal constructor(context: Context, params: WorkerParameters,
    private val runner: ApplyRunner, private val dispatch: FetchDispatch, private val events: SyncEvents = SyncEvents.None) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params,
        ApplyRunner(LibraryRepository(context, DictionaryDatabase.get(context))), FetchWork(context), SyncNotifier(context))
    override suspend fun doWork(): Result {
        var continues = false
        return try {
            continues = runner.run(events)
            if (continues) dispatch.applyNow()
            Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } finally {
            if (!continues) events.idle()
        }
    }
}

class FetchWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try { FetchWork(applicationContext).enqueue(); Result.success() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

class PeriodicFetchRunner(private val library: LibraryRepository, private val dispatch: FetchDispatch,
    private val now: () -> Long = System::currentTimeMillis, private val sourceId: String? = null) {
    suspend fun run() {
        library.requestPeriodicFetches(now(), sourceId)
        if (library.database.requests().pending().isNotEmpty()) dispatch.enqueue()
    }
}

class PeriodicFetchWorker internal constructor(context: Context, params: WorkerParameters,
    private val runner: PeriodicFetchRunner) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params,
        PeriodicFetchRunner(LibraryRepository(context), FetchWork(context), sourceId = params.inputData.getString("sourceId")))
    override suspend fun doWork(): Result = try { runner.run(); Result.success() }
    catch (error: Exception) {
        if (error is CancellationException) throw error
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
