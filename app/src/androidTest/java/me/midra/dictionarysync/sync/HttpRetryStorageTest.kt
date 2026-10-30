package me.midra.dictionarysync.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.library.LibraryRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No real HTTP or UserDictionary operations. Clock advances without sleeping. */
@RunWith(AndroidJUnit4::class)
class HttpRetryStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun memory() = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
    private suspend fun source(db: DictionaryDatabase, id: String = "A") = db.library().addSource(SourceEntity(id, "合成$id", "https://example.org/$id"))

    @Test fun transientFailuresStopAfterFiveRetriesWithoutChangingOldCandidateOrProviderState() = runBlocking {
        val db = memory()
        try {
            source(db); var now = System.currentTimeMillis(); var calls = 0
            val repo = LibraryRepository(context, db, now = { now }) { _, _, _ -> calls++; throw SocketTimeoutException() }
            repo.requests.submit(listOf("A"), FetchReason.MANUAL)
            for (attempt in 1..6) {
                repo.executeFetch(repo.requests.ready(now).single())
                val saved = db.requests().request("A")!!
                assertEquals(attempt, saved.failureCount)
                if (attempt <= 5) {
                    assertEquals(0L, saved.completedGeneration); assertEquals("HTTP_RETRY", saved.waitReason)
                    assertEquals(now + HttpRetry.backoff(attempt), saved.notBefore)
                    assertTrue(repo.requests.ready(saved.notBefore - 1).isEmpty())
                    repo.executeFetch(saved); assertEquals(attempt, calls) // Before deadline.
                    now = saved.notBefore
                }
            }
            assertEquals(6, calls); assertEquals("FETCH_FAILED", db.requests().request("A")!!.outcome)
            assertTrue(db.requests().pending().isEmpty()); assertNull(db.library().source("A")!!.latestSnapshot)
            assertNull(db.library().source("A")!!.syncedAt); assertTrue(db.sync().plans().isEmpty())
        } finally { db.close() }
    }

    @Test fun retryAfterSurvivesManualRequestsReopenAndCompletedGeneration() = runBlocking {
        val name = "http-retry-reopen"; context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, DictionaryDatabase::class.java, name).build()
        try {
            source(db); val now = System.currentTimeMillis()
            val repo = LibraryRepository(context, db, now = { now }) { _, _, _ -> throw DictionaryProblem("HTTP_429", retryAfter = "3600") }
            repo.fetch("A")
            val pending = db.requests().pending().single(); assertEquals(now + 3_600_000, pending.notBefore)
            assertEquals(1, pending.failureCount); assertEquals(pending.notBefore, db.library().source("A")!!.serverNotBefore)
            val merged = repo.requests.submit(listOf("A"), FetchReason.MANUAL).single()
            assertEquals(pending.notBefore, merged.notBefore); assertEquals(0, merged.failureCount)
            repo.requests.finish(merged, "FETCH_FAILED") // Final outcomes must not erase the server hold.
            db.close(); db = Room.databaseBuilder(context, DictionaryDatabase::class.java, name).build()
            val fresh = PendingRequests(db).submit(listOf("A"), FetchReason.MANUAL).single()
            assertEquals(pending.notBefore, fresh.notBefore); assertEquals("SERVER_WAIT", fresh.waitReason)
            assertTrue(PendingRequests(db).ready(now).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun retryAfterArrivingDuringNewSubmissionHoldsNewGenerationWithoutChargingItsBudget() = runBlocking {
        val db = memory(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            source(db); val now = System.currentTimeMillis()
            val repo = LibraryRepository(context, db, now = { now }) { _, _, _ ->
                started.countDown(); check(release.await(10, TimeUnit.SECONDS)); throw DictionaryProblem("HTTP_503", retryAfter = "3600")
            }
            val job = async(Dispatchers.IO) { repo.fetch("A") }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
                repo.requests.submit(listOf("A"), FetchReason.MANUAL)
            } finally { release.countDown() }
            job.await()
            val saved = db.requests().pending().single()
            assertEquals(2L, saved.generation); assertEquals(1L, saved.completedGeneration)
            assertEquals(0, saved.failureCount); assertEquals(now + 3_600_000, saved.notBefore)
            assertEquals("SERVER_WAIT", saved.waitReason)
        } finally { release.countDown(); db.close() }
    }

    @Test fun wifiWaitDoesNotConsumeFailuresOrOverrideHttpTimerAndOtherSourceRuns() = runBlocking {
        val db = memory()
        try {
            source(db); source(db, "B"); var now = System.currentTimeMillis(); var mode = "timeout"; val calls = mutableListOf<String>()
            val repo = LibraryRepository(context, db, now = { now }) { url, _, _ ->
                calls += url
                if (url.endsWith("A")) { if (mode == "timeout") throw SocketTimeoutException() else throw DictionaryProblem("WIFI_WAIT") }
                val bytes = "r\tw\tp".toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            repo.requests.submit(listOf("A", "B"), FetchReason.MANUAL)
            val runner = FetchRunner(repo) { now }
            assertEquals(0L, runner.runOne())
            val retryAt = db.requests().request("A")!!.notBefore
            assertEquals(now + 30_000, retryAt) // Not replaced by the 15-minute condition timer.
            assertEquals(30_000L, runner.runOne()); assertEquals("FETCHED", db.library().source("B")!!.state)
            now = retryAt; mode = "wifi"; runner.runOne()
            val waiting = db.requests().request("A")!!
            assertEquals(1, waiting.failureCount); assertEquals(now + FetchRunner.WAIT_MILLIS, waiting.notBefore)
            assertEquals("WIFI_WAIT", waiting.waitReason); assertEquals(3, calls.size)
        } finally { db.close() }
    }

    @Test fun permanentHttpAndInvalidBodyDoNotRetryWhileSuccessAfterRetryKeepsHistory() = runBlocking {
        val db = memory()
        try {
            source(db); var now = System.currentTimeMillis(); var mode = "404"
            val repo = LibraryRepository(context, db, now = { now }) { url, _, _ ->
                if (mode == "404") throw DictionaryProblem("HTTP_404")
                if (mode == "500") throw DictionaryProblem("HTTP_500")
                val bytes = if (mode == "html") "<html>bad</html>".toByteArray() else "r\tw\tp".toByteArray()
                Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            repo.fetch("A"); assertTrue(db.requests().pending().isEmpty())
            mode = "ok"; repo.fetch("A"); val before = db.library().source("A")!!
            mode = "html"; repo.fetch("A"); assertTrue(db.requests().pending().isEmpty())
            assertEquals(before.latestSnapshot, db.library().source("A")!!.latestSnapshot)
            mode = "500"; repo.fetch("A"); val waiting = db.requests().pending().single()
            assertEquals(before.fetchedAt, db.library().source("A")!!.fetchedAt)
            now = waiting.notBefore; mode = "ok"; repo.executeFetch(waiting)
            assertEquals("FETCHED", db.requests().request("A")!!.outcome); assertEquals(1, db.requests().request("A")!!.failureCount)
            assertNull(db.library().source("A")!!.activeSnapshot); assertTrue(db.sync().references().isEmpty())
        } finally { db.close() }
    }

    @Test fun failedRetryPersistenceRollsBackHoldAndFailureCountAndRetainsRequest() = runBlocking {
        val db = memory()
        try {
            source(db)
            val repo = LibraryRepository(context, db) { _, _, _ -> throw DictionaryProblem("HTTP_503", retryAfter = "3600") }
            val request = repo.requests.submit(listOf("A"), FetchReason.MANUAL).single()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_retry BEFORE UPDATE OF failureCount ON pending_requests WHEN NEW.failureCount != OLD.failureCount BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            try { repo.executeFetch(request); fail() } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(0L, db.library().source("A")!!.serverNotBefore)
            assertEquals(request, db.requests().pending().single())
        } finally { db.close() }
    }

    @Test fun retryLimitStillPreservesLastServerDeadlineForNextManualGeneration() = runBlocking {
        val db = memory()
        try {
            source(db); var now = System.currentTimeMillis(); var calls = 0
            val repo = LibraryRepository(context, db, now = { now }) { _, _, _ -> calls++; throw DictionaryProblem("HTTP_429", retryAfter = "3600") }
            repo.requests.submit(listOf("A"), FetchReason.MANUAL)
            repeat(6) {
                val request = repo.requests.ready(now).single()
                repo.executeFetch(request)
                if (it < 5) now = db.requests().request("A")!!.notBefore
            }
            val last = db.requests().request("A")!!
            assertEquals(6, calls); assertEquals(6, last.failureCount); assertEquals(last.generation, last.completedGeneration)
            val next = repo.requests.submit(listOf("A"), FetchReason.MANUAL, now = now).single()
            assertEquals(now + 3_600_000, next.notBefore); assertEquals(0, next.failureCount)
            repo.executeFetch(next); assertEquals(6, calls)
        } finally { db.close() }
    }
}
