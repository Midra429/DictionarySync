package me.midra.dictionarysync.sync

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.AppContainer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.NoDispatch
import me.midra.dictionarysync.testApp

/** Isolated business DBs and synthetic HTTP. Test WorkManager uses its own in-memory DB/delegate. */
@RunWith(AndroidJUnit4::class)
class FetchWorkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Dispatch : FetchDispatch {
        var wakes = 0; var delay: Long? = null; var cancelled = 0; var automatic: Boolean? = null
        var fail = false
        override suspend fun enqueue() { if (fail) error("synthetic enqueue loss"); wakes++ }
        override suspend fun wakeAfter(delayMillis: Long) { if (fail) error("synthetic enqueue loss"); delay = delayMillis }
        override suspend fun cancelWake() { cancelled++ }
        override suspend fun sources(sources: List<SourceEntity>) { automatic = sources.any { it.enabled && it.lifecycle == SourceLifecycle.ACTIVE } }
    }
    private fun memory() = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
    private fun library(db: DictionaryDatabase, dispatch: FetchDispatch = NoDispatch, response: (String) -> String = { "よみ\tWorker合成語\t名詞" }) =
        testApp(context, db, dispatch = dispatch) { url, _, _ ->
        val bytes = response(url).toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
    }
    private suspend fun source(db: DictionaryDatabase, id: String = "A", fetchedAt: Long? = null, lifecycle: SourceLifecycle = SourceLifecycle.ACTIVE) {
        db.library().addSource(SourceEntity(id, "合成$id", "https://example.org/$id", fetchedAt = fetchedAt, lifecycle = lifecycle))
    }
    private fun runner(repo: AppContainer, now: () -> Long = System::currentTimeMillis) =
        FetchRunner(repo.database, repo.fetcher, repo.requests, repo.applies, now)
    private fun worker(runner: FetchRunner, dispatch: Dispatch, attempts: Int = 0) = TestListenableWorkerBuilder<FetchWorker>(context)
        .setRunAttemptCount(attempts).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                FetchWorker(appContext, workerParameters, runner, dispatch)
        }).build()

    @Test fun saveThenEnqueueFailureIsRepairedWithoutLosingTheGeneration() = runBlocking {
        val db = memory()
        try {
            source(db); val dispatch = Dispatch(); val repo = library(db, dispatch); val requests = repo.scheduler
            dispatch.fail = true
            try { requests.requestOne("A"); fail() } catch (_: IllegalStateException) { }
            assertEquals(1L, db.requests().pending().single().generation)
            dispatch.fail = false; requests.repair()
            assertEquals(1, dispatch.wakes); assertEquals(1L, db.requests().pending().single().generation)
            assertEquals(ListenableWorker.Result.success(), worker(runner(repo), dispatch).doWork())
            assertEquals(FetchOutcome.FETCHED, db.requests().request("A")!!.outcome)
            assertTrue(db.sync().plans().isEmpty()); assertNull(db.library().source("A")!!.activeSnapshot)
        } finally { db.close() }
    }

    @Test fun workerSuccessDoesNotMeanBusinessSuccessAndEmptyCandidateIsNeverApproved() = runBlocking {
        val db = memory()
        try {
            source(db); var body = "<html>failed</html>"
            val repo = library(db) { body }; val dispatch = Dispatch()
            repo.requests.submit(listOf("A"), FetchReason.MANUAL)
            assertEquals(ListenableWorker.Result.success(), worker(runner(repo), dispatch).doWork())
            assertEquals(FetchOutcome.FETCH_FAILED, db.requests().request("A")!!.outcome)
            body = "# valid empty TSV"
            repo.requests.submit(listOf("A"), FetchReason.MANUAL)
            assertEquals(ListenableWorker.Result.success(), worker(runner(repo), dispatch).doWork())
            assertEquals(FetchOutcome.FETCHED, db.requests().request("A")!!.outcome)
            assertTrue(db.sync().plans().isEmpty()); assertTrue(db.sync().references().isEmpty())
        } finally { db.close() }
    }

    @Test fun wifiWaitDefersOnlyThatSourceAndDoesNotStarveAnotherSource() = runBlocking {
        val db = memory()
        try {
            source(db); source(db, "B")
            val repo = library(db) { if (it.endsWith("/A")) throw DictionaryProblem("WIFI_WAIT") else "よみ\t成功語\t名詞" }
            val dispatch = Dispatch(); val now = System.currentTimeMillis()
            repo.requests.submit(listOf("A", "B"), FetchReason.ALL_SOURCES, now = now)
            assertEquals(ListenableWorker.Result.success(), worker(runner(repo) { now }, dispatch).doWork())
            assertEquals(1, dispatch.wakes)
            assertEquals(now + FetchRunner.WAIT_MILLIS, db.requests().request("A")!!.notBefore)
            assertEquals(0L, db.requests().request("A")!!.completedGeneration)
            worker(runner(repo) { now }, dispatch).doWork()
            assertEquals(FetchOutcome.FETCHED, db.requests().request("B")!!.outcome)
            assertEquals(FetchRunner.WAIT_MILLIS, dispatch.delay)
            val calls = db.library().snapshotIds("B").size
            worker(runner(repo) { now }, dispatch).doWork()
            assertEquals(calls, db.library().snapshotIds("B").size)
            assertEquals(FetchState.NETWORK_WAIT, db.library().source("A")!!.state)
        } finally { db.close() }
    }

    @Test fun infrastructureFailureRetriesWithoutAckThenLeavesRequestForStartupRepair() = runBlocking {
        val db = memory()
        try {
            source(db); val repo = library(db); val dispatch = Dispatch()
            repo.requests.submit(listOf("A"), FetchReason.MANUAL)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_worker BEFORE INSERT ON snapshots BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END")
            assertEquals(ListenableWorker.Result.retry(), worker(runner(repo), dispatch).doWork())
            assertEquals(ListenableWorker.Result.failure(), worker(runner(repo), dispatch, 5).doWork())
            assertEquals(0L, db.requests().pending().single().completedGeneration)
            assertTrue(db.library().snapshotIds("A").isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_worker")
            worker(runner(repo), dispatch).doWork()
            assertEquals(1L, db.requests().request("A")!!.completedGeneration)
        } finally { db.close() }
    }

    @Test fun workerCancellationKeepsRequestAndDoesNotPublishCandidate() = runBlocking {
        val db = memory()
        try {
            source(db); val repo = library(db) { throw CancellationException("synthetic stop") }
            repo.requests.submit(listOf("A"), FetchReason.MANUAL)
            try { worker(runner(repo), Dispatch()).doWork(); fail() } catch (_: CancellationException) { }
            assertEquals(0L, db.requests().pending().single().completedGeneration)
            assertTrue(db.library().snapshotIds("A").isEmpty())
        } finally { db.close() }
    }

    @Test fun periodicOnlyRequestsDueActiveSourcesAndRespectsDisabledSetting() = runBlocking {
        val db = memory()
        try {
            val now = System.currentTimeMillis()
            source(db, "A"); source(db, "B", now - 1000); source(db, "C", now - FetchWork.DAY_MILLIS)
            source(db, "D", lifecycle = SourceLifecycle.DELETE_PENDING)
            val repo = library(db); val dispatch = Dispatch()
            suspend fun enable(id: String, value: Boolean) = db.library().updateSource(db.library().source(id)!!.copy(enabled = value))
            enable("A", false); enable("C", false)
            val runner = PeriodicFetchRunner(repo.scheduler, repo.requests, dispatch, { now })
            runner.run(); assertTrue(db.requests().pending().isEmpty()); assertEquals(0, dispatch.wakes)
            enable("A", true); enable("C", true); runner.run()
            assertEquals(setOf("A", "C"), db.requests().pending().map { it.sourceId }.toSet())
            assertTrue(db.requests().pending().all { it.reasons == FetchReason.PERIODIC.mask })
            assertEquals(1, dispatch.wakes); assertTrue(db.sync().plans().isEmpty())
        } finally { db.close() }
    }

    @Test fun futureNotBeforeUsesSeparateWakeTimerWithoutRunningHttp() = runBlocking {
        val db = memory()
        try {
            source(db); var calls = 0; val now = System.currentTimeMillis()
            val repo = library(db) { calls++; "よみ\t合成語\t名詞" }; val dispatch = Dispatch()
            repo.requests.submit(listOf("A"), FetchReason.MANUAL, now + 123456, now)
            worker(runner(repo) { now }, dispatch).doWork()
            assertEquals(0, calls); assertEquals(123456L, dispatch.delay); assertEquals(0, dispatch.wakes)
            assertEquals(0L, db.requests().pending().single().completedGeneration)
        } finally { db.close() }
    }

    @Suppress("RestrictedApi")
    @Test fun uniqueChainConstraintsTimerAndPeriodicUpdateUseIsolatedWorkManager() {
        val original = WorkManagerImpl.getInstance(context)
        var calls = 0
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                object : Worker(appContext, workerParameters) { override fun doWork(): Result { calls++; return Result.success() } }
        }
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        val manager = WorkManager.getInstance(context)
        try {
            val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
            val first = FetchWork.request(); val second = FetchWork.request()
            assertEquals(NetworkType.CONNECTED, first.workSpec.constraints.requiredNetworkType)
            assertTrue(first.workSpec.constraints.requiresStorageNotLow())
            manager.enqueueUniqueWork(FetchWork.CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, first).result.get()
            manager.enqueueUniqueWork(FetchWork.CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, second).result.get()
            assertEquals(0, calls); assertEquals(WorkInfo.State.BLOCKED, manager.getWorkInfoById(second.id).get()!!.state)
            driver.setAllConstraintsMet(first.id)
            assertEquals(1, calls); driver.setAllConstraintsMet(second.id); assertEquals(2, calls)
            val dispatch = FetchWork(context, manager)
            runBlocking { dispatch.wakeAfter(100000); dispatch.enqueue() }
            // The delayed wake is independent; the manual chain has an immediately eligible leaf.
            val manual = manager.getWorkInfosForUniqueWork(FetchWork.CHAIN).get().single { it.state == WorkInfo.State.ENQUEUED }
            driver.setAllConstraintsMet(manual.id); assertEquals(3, calls)
            val timer = manager.getWorkInfosForUniqueWork(FetchWork.WAKE).get().single()
            assertEquals(WorkInfo.State.ENQUEUED, timer.state); driver.setInitialDelayMet(timer.id); assertEquals(4, calls)
            val a = SourceEntity("A", "1時間", "https://example.org/a", syncIntervalHours = 1)
            val b = SourceEntity("B", "6時間", "https://example.org/b", syncIntervalHours = 6)
            runBlocking { dispatch.sources(listOf(a, b)); dispatch.sources(listOf(a, b)) }
            val periodic = manager.getWorkInfosForUniqueWork(FetchWork.periodicName("A")).get().filter { !it.state.isFinished }
            assertEquals(1, periodic.size)
            assertEquals(1, manager.getWorkInfosForUniqueWork(FetchWork.periodicName("B")).get().count { !it.state.isFinished })
            driver.setInitialDelayMet(periodic.single().id); assertEquals(5, calls)
            driver.setPeriodDelayMet(periodic.single().id); assertEquals(6, calls)
            runBlocking { dispatch.sources(listOf(a.copy(syncIntervalHours = 12), b.copy(enabled = false))) }
            assertEquals(periodic.single().id, manager.getWorkInfosForUniqueWork(FetchWork.periodicName("A")).get().single { !it.state.isFinished }.id)
            assertTrue(manager.getWorkInfosForUniqueWork(FetchWork.periodicName("B")).get().all { it.state.isFinished })
            runBlocking { dispatch.sources(listOf(a.copy(enabled = false), b.copy(enabled = false))) }
            assertTrue(manager.getWorkInfosForUniqueWork(FetchWork.periodicName("A")).get().all { it.state.isFinished })
        } finally {
            manager.cancelAllWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
            WorkManagerImpl.setDelegate(original)
        }
    }
}
