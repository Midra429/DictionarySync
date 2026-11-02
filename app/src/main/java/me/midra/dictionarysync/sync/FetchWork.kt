package me.midra.dictionarysync.sync

import android.content.Context
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.appContainer
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.library.SourceFetcher

/*
 * Background work. WorkManager stores the Worker class names and unique work names below: renaming or moving either
 * strands work already scheduled on installed devices.
 */

/** Enqueues the background chains. Implemented by [FetchWork]; replaced in tests. */
interface FetchDispatch {
    suspend fun enqueue()
    /** A fetch the user just asked for. */
    suspend fun enqueueNow() = enqueue()
    /** Shared-dictionary writes the user just asked for (no network needed). */
    suspend fun applyNow() = enqueueNow()
    suspend fun wakeAfter(delayMillis: Long)
    suspend fun cancelWake()
    /** Schedules the periodic fetch of every syncable source and cancels the others'. */
    suspend fun sources(sources: List<SourceEntity>)
}

/** The WorkManager chains: one fetch chain, one apply chain, a wake-up timer and one periodic request per source. */
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
    override suspend fun sources(sources: List<SourceEntity>) {
        for (source in sources) {
            val name = periodicName(source.id)
            if (source.syncable) {
                manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(source.id, source.syncIntervalHours)).await()
            } else manager.cancelUniqueWork(name).await()
        }
    }

    companion object {
        const val CHAIN = "dictionary-fetch"
        const val WAKE = "dictionary-fetch-wake"
        const val PERIODIC = "dictionary-periodic"
        const val APPLY = "dictionary-apply"
        const val SOURCE_ID = "sourceId"
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        fun periodicName(id: String) = "$PERIODIC:$id"
        fun periodicRequest(id: String? = null, hours: Int = 24) = PeriodicWorkRequestBuilder<PeriodicFetchWorker>(hours.toLong(), TimeUnit.HOURS)
            .setInputData(workDataOf(SOURCE_ID to id))
            .setInitialDelay(hours.toLong(), TimeUnit.HOURS).build()
        /** Provider writes need no network; expedited on Android 12+ because the user is waiting for them. */
        fun applyRequest(sdk: Int = Build.VERSION.SDK_INT) = OneTimeWorkRequestBuilder<ApplyWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply { if (sdk >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        fun request(expedited: Boolean = false, sdk: Int = Build.VERSION.SDK_INT) = OneTimeWorkRequestBuilder<FetchWorker>()
            // Coarse wake-up constraint. The download route still proves and pins actual Wi-Fi.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Before Android 12 expedited work needs a foreground notification; there it stays a regular request.
            .apply { if (expedited && sdk >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
    }
}

/**
 * Turns durable requests into scheduled work. Saves first, then enqueues; [repair] fixes the intentionally non-atomic
 * Room/enqueue boundary at startup and after every user action.
 */
class FetchScheduler(
    private val context: Context,
    private val db: DictionaryDatabase,
    private val requests: PendingRequests,
    private val applies: ApplyQueue,
    private val dispatch: FetchDispatch,
) {
    private val library = db.library()

    suspend fun requestOne(id: String) {
        requests.submit(listOf(id), FetchReason.MANUAL)
        dispatch.enqueueNow()
    }

    /** Requests a fetch of every syncable source and wakes the fetch chain. */
    suspend fun requestAll() {
        submitAll()
        repair(userRequested = true)
    }

    /** Durably requests a fetch of every syncable source, without scheduling it. */
    suspend fun submitAll(): List<PendingRequest> =
        SyncCoordinator.locked(context) { requests.submit(library.sources().filter { it.syncable }.map { it.id }, FetchReason.ALL_SOURCES) }

    /** Requests every syncable source (or just [sourceId]) whose interval has passed. */
    suspend fun requestPeriodic(now: Long, sourceId: String? = null): Unit = SyncCoordinator.locked(context) {
        requests.submit(library.sources().filter { it.syncable && (sourceId == null || it.id == sourceId) && it.fetchDue(now) }.map { it.id },
            FetchReason.PERIODIC, now = now)
        Unit
    }

    suspend fun repair(userRequested: Boolean = false) {
        dispatch.sources(SyncCoordinator.locked(context) { library.allSources() })
        if (requests.pending().isNotEmpty()) { if (userRequested) dispatch.enqueueNow() else dispatch.enqueue() }
        // Pending shared-dictionary writes go to the apply chain, which does not wait for a network.
        if (!applies.isEmpty()) dispatch.applyNow()
    }
}

/** One fetch per pass bounds a Worker's run and yields between sources. All state lives in Room. */
class FetchRunner(
    private val db: DictionaryDatabase,
    private val fetcher: SourceFetcher,
    private val requests: PendingRequests,
    private val applies: ApplyQueue,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Returns the delay until the next pass: 0 to continue at once, null when nothing is pending. */
    suspend fun runOne(events: SyncEvents = SyncEvents.None): Long? {
        fetcher.markInterrupted()
        requests.ready(now()).firstOrNull()?.let { request ->
            db.library().source(request.sourceId)?.let(events::fetching)
            fetcher.execute(request)
            val current = requests.request(request.sourceId)
            // Only this run can acknowledge its generation as failed; retries still pending are not reported yet.
            if (current != null && current.completedGeneration >= request.generation && current.outcome == FetchOutcome.FETCH_FAILED)
                db.library().source(request.sourceId)?.let { events.fetchFailed(it, current.error ?: FetchOutcome.FETCH_FAILED.name) }
            val wait = current?.waitReason
            if (current?.generation == request.generation && current.pending && wait != null && wait.network) {
                // Network conditions consume neither the generation nor the retry budget. No tight loop.
                requests.defer(request, wait, Math.addExact(now(), WAIT_MILLIS))
            }
        }
        applies.apply(limit = 1, events = events)
        if (!applies.isEmpty()) return 0L
        return requests.nextDelay(now())
    }

    companion object { const val WAIT_MILLIS = 15 * 60 * 1000L }
}

/** Requests the periodic fetch of one source (or of every due source) and wakes the fetch chain. */
class PeriodicFetchRunner(private val scheduler: FetchScheduler, private val requests: PendingRequests, private val dispatch: FetchDispatch,
    private val now: () -> Long = System::currentTimeMillis, private val sourceId: String? = null) {
    suspend fun run() {
        scheduler.requestPeriodic(now(), sourceId)
        if (requests.pending().isNotEmpty()) dispatch.enqueue()
    }
}

/** Retries a failed pass a bounded number of times; startup repairs whatever is left. Cancellation propagates. */
private suspend fun CoroutineWorker.bounded(block: suspend () -> Unit): ListenableWorker.Result = try {
    block()
    ListenableWorker.Result.success()
} catch (error: Exception) {
    if (error is CancellationException) throw error
    if (runAttemptCount < MAX_ATTEMPTS) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
}

private const val MAX_ATTEMPTS = 5

class FetchWorker internal constructor(context: Context, params: WorkerParameters,
    private val runner: FetchRunner, private val dispatch: FetchDispatch, private val events: SyncEvents = SyncEvents.None) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params,
        context.appContainer.fetchRunner, context.appContainer.dispatch, context.appContainer.events)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // The progress notification stays up while the next pass is queued right behind this one.
        var continues = false
        try {
            // Success acknowledges a scheduling pass, not a successful fetch or Provider apply. Storage and enqueue
            // errors never acknowledge business requests.
            bounded {
                val delay = runner.runOne(events)
                continues = delay == 0L
                when (delay) {
                    null -> dispatch.cancelWake()
                    0L -> dispatch.enqueue()
                    else -> dispatch.wakeAfter(delay)
                }
            }
        } finally {
            if (!continues) events.idle()
        }
    }
}

/** Applies desired shared-dictionary states (enable, disable, delete, new generations) through the sync engine. */
class ApplyWorker internal constructor(context: Context, params: WorkerParameters,
    private val applies: ApplyQueue, private val dispatch: FetchDispatch, private val events: SyncEvents = SyncEvents.None) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params,
        context.appContainer.applies, context.appContainer.dispatch, context.appContainer.events)

    override suspend fun doWork(): Result {
        var continues = false
        return try {
            bounded {
                continues = applies.pass(events)
                if (continues) dispatch.applyNow()
            }
        } finally {
            if (!continues) events.idle()
        }
    }
}

class FetchWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = bounded { applicationContext.appContainer.dispatch.enqueue() }
}

class PeriodicFetchWorker internal constructor(context: Context, params: WorkerParameters,
    private val runner: PeriodicFetchRunner) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, context.appContainer.run {
        PeriodicFetchRunner(scheduler, requests, dispatch, now, params.inputData.getString(FetchWork.SOURCE_ID))
    })
    override suspend fun doWork(): Result = bounded { runner.run() }
}
