package me.midra.dictionarysync.sync

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.AppContainer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.testApp

/** Synthetic HTTP and independent Room databases only. No shared UserDictionary operations. */
@RunWith(AndroidJUnit4::class)
class PendingRequestStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun memory() = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
    private fun library(db: DictionaryDatabase, response: (String) -> String = { "よみ\t永続合成語\t名詞" }) =
        testApp(context, db) { url, _, _ ->
            val bytes = response(url).toByteArray()
            Download(bytes, Downloader.sha256(bytes), url, "text/plain")
        }
    private suspend fun source(db: DictionaryDatabase, id: String = "A") {
        db.library().addSource(SourceEntity(id, "合成$id", "https://example.org/$id"))
    }

    @Test fun oldAckDoesNotCompleteNewGenerationAndDuplicateOrFutureAckIsIgnored() = runBlocking {
        val db = memory()
        try {
            source(db); val queue = PendingRequests(db)
            val first = queue.submit(listOf("A"), FetchReason.MANUAL, now = 10).single()
            val second = queue.submit(listOf("A", "A"), FetchReason.ALL_SOURCES, now = 11).single()
            assertEquals(2L, second.generation); assertEquals(3, second.reasons)
            assertTrue(queue.finish(first, FetchOutcome.FETCHED, now = 12))
            val pending = db.requests().pending().single()
            assertEquals(2L, pending.generation); assertEquals(1L, pending.completedGeneration)
            assertFalse(queue.finish(first, FetchOutcome.FETCH_FAILED))
            assertFalse(queue.finish(second.copy(generation = 3), FetchOutcome.FETCHED))
            assertTrue(queue.finish(second, FetchOutcome.FETCH_FAILED, "synthetic", now = 13))
            assertTrue(db.requests().pending().isEmpty())
            assertEquals(FetchOutcome.FETCH_FAILED, db.requests().request("A")!!.outcome)
            val third = queue.submit(listOf("A"), FetchReason.MANUAL).single()
            assertEquals(3L, third.generation); assertEquals(1, third.reasons)
        } finally { db.close() }
    }

    @Test fun oldConfigAckAndDeferCannotConsumeOrDelayNewConfig() = runBlocking {
        val db = memory()
        try {
            source(db); val queue = PendingRequests(db)
            val old = queue.submit(listOf("A"), FetchReason.MANUAL).single()
            db.library().updateSource(db.library().source("A")!!.copy(revision = 2))
            val new = queue.submit(listOf("A"), FetchReason.MANUAL).single()
            assertFalse(queue.finish(old, FetchOutcome.FETCHED)); assertFalse(queue.defer(old, WaitReason.WIFI_WAIT, 9999))
            assertEquals(new, db.requests().request("A"))
            assertTrue(queue.isCurrent(new)); assertFalse(queue.isCurrent(old))
        } finally { db.close() }
    }

    @Test fun notBeforeSurvivesMergingAndStaleDeferralCannotDelayNewRequest() = runBlocking {
        val db = memory()
        try {
            source(db); val queue = PendingRequests(db)
            val first = queue.submit(listOf("A"), FetchReason.MANUAL, notBefore = 1000, now = 10).single()
            assertTrue(queue.ready(999).isEmpty()); assertFalse(queue.isCurrent(first, 999))
            assertTrue(queue.defer(first, WaitReason.NETWORK_WAIT, 2000))
            val second = queue.submit(listOf("A"), FetchReason.MANUAL, notBefore = 0, now = 11).single()
            assertEquals(2000L, second.notBefore); assertTrue(queue.ready(1999).isEmpty())
            assertFalse(queue.defer(first, WaitReason.WIFI_WAIT, 9000)) // A superseded generation is never deferred.
            assertTrue(queue.finish(first, FetchOutcome.FETCHED))
            assertEquals(WaitReason.NETWORK_WAIT, db.requests().request("A")!!.waitReason)
            assertEquals(2L, queue.ready(2000).single().generation)
        } finally { db.close() }
    }

    @Test fun concurrentSubmissionsProduceMonotonicGenerations() = runBlocking {
        val db = memory()
        try {
            source(db); val queue = PendingRequests(db)
            val generations = (1..24).map { async(Dispatchers.Default) { queue.submit(listOf("A"), FetchReason.MANUAL).single().generation } }.awaitAll()
            assertEquals((1L..24L).toList(), generations.sorted())
            assertEquals(24L, db.requests().pending().single().generation)
        } finally { db.close() }
    }

    @Test fun requestSavedBeforeExecutionSurvivesReopenAndIsResumedExactlyOnce() = runBlocking {
        val name = "pending-reopen"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, DictionaryDatabase::class.java, name).build()
        try {
            source(db); PendingRequests(db).submit(listOf("A"), FetchReason.MANUAL)
            db.close(); db = Room.databaseBuilder(context, DictionaryDatabase::class.java, name).build()
            var calls = 0
            val repo = library(db) { calls++; "よみ\t永続合成語\t名詞" }
            repo.fetcher.resumePending(); repo.fetcher.resumePending()
            assertEquals(1, calls); assertEquals(FetchOutcome.FETCHED, db.requests().request("A")!!.outcome)
            assertEquals(1L, db.requests().request("A")!!.completedGeneration)
            assertNotNull(db.library().source("A")!!.latestSnapshot)
            assertNull(db.library().source("A")!!.activeSnapshot)
            assertTrue(db.sync().plans().isEmpty()); assertTrue(db.sync().references().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun newRequestDuringHttpRemainsPendingAfterOldCandidateCommits() = runBlocking {
        val db = memory()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            source(db); var calls = 0
            val repo = library(db) {
                calls++
                if (calls == 1) { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                "よみ\t世代$calls\t名詞"
            }
            val job = async(Dispatchers.IO) { repo.fetcher.fetch("A") }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
                repo.requests.submit(listOf("A"), FetchReason.ALL_SOURCES)
            } finally { release.countDown() }
            job.await()
            assertEquals(1, calls); assertEquals(1L, db.requests().pending().single().completedGeneration)
            repo.fetcher.resumePending()
            assertEquals(2, calls); assertTrue(db.requests().pending().isEmpty())
            assertEquals(2L, db.requests().request("A")!!.completedGeneration)
        } finally { release.countDown(); db.close() }
    }

    @Test fun cancelledBatchHasAllRequestsDurableAndRestartProcessesRemainingSources() = runBlocking {
        val db = memory()
        try {
            source(db, "A"); source(db, "B")
            val repo = library(db) { throw CancellationException("synthetic process loss") }
            try { repo.fetcher.fetchAll(); fail() } catch (_: CancellationException) { }
            assertEquals(2, db.requests().pending().size)
            assertEquals(FetchState.FETCHING, db.library().source("A")!!.state)
            val calls = mutableListOf<String>()
            library(db) { calls += it; "よみ\t永続合成語\t名詞" }.fetcher.resumePending()
            assertEquals(2, calls.size); assertTrue(db.requests().pending().isEmpty())
            assertEquals(2, db.library().sources().count { it.state == FetchState.FETCHED })
        } finally { db.close() }
    }

    @Test fun failedCommitRollsBackCandidateAndAckTogetherAndCanResume() = runBlocking {
        val db = memory()
        try {
            source(db)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_request_ack BEFORE UPDATE OF completedGeneration ON pending_requests BEGIN SELECT RAISE(ABORT, 'synthetic ack failure'); END")
            val repo = library(db)
            try { repo.fetcher.fetch("A"); fail() } catch (_: android.database.SQLException) { }
            assertNull(db.library().source("A")!!.latestSnapshot); assertTrue(db.library().snapshotIds("A").isEmpty())
            assertEquals(0L, db.requests().pending().single().completedGeneration)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_request_ack")
            repo.fetcher.resumePending()
            assertEquals(1L, db.requests().request("A")!!.completedGeneration)
            assertEquals(1, db.library().snapshotIds("A").size)
        } finally { db.close() }
    }

    @Test fun networkWaitRemainsPendingButMalformedBodyIsHandledAsFailureWithoutLosingOldVersion() = runBlocking {
        val db = memory()
        try {
            source(db); var mode = "ok"
            val repo = library(db) {
                when (mode) { "wait" -> throw DictionaryProblem("WIFI_WAIT"); "bad" -> "<html>failure</html>"; else -> "よみ\t永続合成語\t名詞" }
            }
            repo.fetcher.fetch("A"); val old = db.library().source("A")!!
            mode = "wait"; repo.fetcher.fetch("A")
            assertEquals(WaitReason.WIFI_WAIT, db.requests().pending().single().waitReason)
            assertEquals(1L, db.requests().request("A")!!.completedGeneration)
            assertEquals(FetchState.NETWORK_WAIT, db.library().source("A")!!.state)
            mode = "bad"; repo.fetcher.resumePending()
            assertTrue(db.requests().pending().isEmpty()); assertEquals(FetchOutcome.FETCH_FAILED, db.requests().request("A")!!.outcome)
            assertEquals(old.latestSnapshot, db.library().source("A")!!.latestSnapshot)
            assertEquals(old.fetchedAt, db.library().source("A")!!.fetchedAt)
            assertNull(db.library().source("A")!!.activeSnapshot)
        } finally { db.close() }
    }

    @Test fun staleSettingsDeletedAndDeletionPendingRequestsNeverStartHttp() = runBlocking {
        val db = memory()
        try {
            source(db, "A"); source(db, "B"); source(db, "C")
            val queue = PendingRequests(db)
            queue.submit(listOf("A", "B", "C"), FetchReason.ALL_SOURCES)
            db.library().updateSource(db.library().source("A")!!.copy(revision = 2))
            db.library().updateSource(db.library().source("B")!!.copy(lifecycle = SourceLifecycle.DELETE_PENDING))
            db.library().deleteSource("C")
            var calls = 0
            library(db) { calls++; "よみ\t永続合成語\t名詞" }.fetcher.resumePending()
            assertEquals(0, calls); assertTrue(db.requests().pending().isEmpty())
            assertEquals(FetchOutcome.SETTINGS_CHANGED, db.requests().request("A")!!.outcome)
            assertEquals(FetchOutcome.SOURCE_REMOVED, db.requests().request("B")!!.outcome)
            assertEquals(FetchOutcome.SOURCE_REMOVED, db.requests().request("C")!!.outcome)
        } finally { db.close() }
    }

    @Test fun localRemovalRetainsRequestResultAndBatchSubmissionRollsBackOnInvalidSource() = runBlocking {
        val db = memory()
        try {
            source(db); val repo = library(db)
            try { repo.requests.submit(listOf("A", "missing"), FetchReason.ALL_SOURCES); fail() } catch (_: SyncStopped) { }
            assertNull(db.requests().request("A"))
            repo.requests.submit(listOf("A"), FetchReason.MANUAL)
            assertTrue(repo.sourceSync.delete("A")) // Never applied: deleted without the Provider.
            assertTrue(db.requests().pending().isEmpty()); assertEquals(FetchOutcome.SOURCE_REMOVED, db.requests().request("A")!!.outcome)
            assertEquals(SourceLifecycle.DELETED, db.library().source("A")!!.lifecycle)
        } finally { db.close() }
    }
}
