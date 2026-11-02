package me.midra.dictionarysync.library

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.probe.*
import me.midra.dictionarysync.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.seed
import me.midra.dictionarysync.testApp
import me.midra.dictionarysync.fetch
import me.midra.dictionarysync.resumePendingFetches

/** Synthetic HTTP and Provider only; never reads/writes shared UserDictionary. */
@RunWith(AndroidJUnit4::class)
class HttpValidatorStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun memory() = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
    private fun body(url: String, text: String = "よみ\t合成語\t名詞") = text.toByteArray().let { Download(it, Downloader.sha256(it), url, "text/plain", "\"validated\"") }
    private fun unchanged(url: String) = Download(byteArrayOf(), "", url, null, notModified = true)
    private class Gateway : SyncGateway {
        val rows = linkedMapOf<Long, ProbeRow>()
        override fun fingerprint() = "http-synthetic"
        override fun checkAccess() = fingerprint()
        override fun find(key: SyncKey) = rows.values.filter { key.matches(it) }
        override fun byId(id: Long) = rows[id]
        override fun insert(value: ProbeValue): Long = ((rows.keys.maxOrNull() ?: 0) + 1).also { rows[it] = value.row(it) }
        override fun delete(row: ProbeRow): Int = error("unexpected delete")
    }
    private suspend fun prepare(db: DictionaryDatabase, engine: SyncEngine, id: String): SyncPreview {
        val source = db.library().source(id)!!; val snapshot = db.library().snapshot(source.latestSnapshot!!)!!
        return engine.prepare(id, source.revision, source.activeSnapshot, snapshot.id, snapshot.bodyHash,
            db.library().entries(snapshot.id).map { SyncKey.of(it.locale, it.word, it.reading) })
    }

    @Test fun notModifiedKeepsCandidatePlanJournalAndSyncTimeButExplicitApplyStillChecksProvider() = runBlocking {
        val db = memory()
        try {
            var calls = 0
            val repo = testApp(context, db) { url, _, validators ->
                if (calls++ == 0) { assertNull(validators); body(url) }
                else { assertEquals("\"validated\"", validators!!.etag); unchanged(url) }
            }
            val id = repo.editor.seed(null, "合成", "https://example.org/a?x=1&x=2", "ja", "TSV", "AUTO")
            repo.fetch(id)
            val gateway = Gateway(); val engine = SyncEngine(RoomSyncStore(db), gateway, "synthetic")
            val preview = prepare(db, engine, id); engine.apply(preview.plan.id, preview.plan.token)
            val next = prepare(db, engine, id)
            val before = repo.database.library().source(id)!!; val journal = db.sync().operations(next.plan.id)
            gateway.rows.clear() // External deletion while HTTP still reports unchanged.
            repo.fetch(id)
            val after = repo.database.library().source(id)!!
            assertEquals(before.latestSnapshot, after.latestSnapshot); assertEquals(before.activeSnapshot, after.activeSnapshot)
            assertEquals(before.syncedAt, after.syncedAt); assertEquals(before.changedAt, after.changedAt)
            assertTrue(after.fetchedAt!! >= before.fetchedAt!!); assertEquals(FetchOutcome.NOT_MODIFIED, db.requests().request(id)!!.outcome)
            assertEquals(next.plan, db.sync().plans().single { it.id == next.plan.id }); assertEquals(journal, db.sync().operations(next.plan.id))
            try { engine.apply(next.plan.id, next.plan.token); fail() } catch (_: SyncStopped) { }
            assertTrue(gateway.rows.isEmpty()); assertNotEquals(PlanState.COMMITTED, db.sync().plans().single { it.id == next.plan.id }.state)
        } finally { db.close() }
    }

    @Test fun emptyCandidateRemainsValidAcross304WithoutCreatingPlan() = runBlocking {
        val db = memory()
        try {
            val repo = testApp(context, db) { url, _, validators -> if (validators == null) body(url, "# valid empty") else unchanged(url) }
            val id = repo.editor.seed(null, "空", "https://example.org/a", "ja", "TSV", "AUTO")
            repo.fetch(id); val first = repo.database.library().source(id)!!
            repo.fetch(id)
            assertEquals(first.latestSnapshot, repo.database.library().source(id)!!.latestSnapshot)
            assertEquals(FetchState.FETCHED, repo.database.library().source(id)!!.state)
            assertEquals(FetchOutcome.NOT_MODIFIED, db.requests().request(id)!!.outcome)
            assertTrue(db.sync().plans().isEmpty()); assertNull(repo.database.library().source(id)!!.activeSnapshot)
        } finally { db.close() }
    }

    @Test fun missingEntriesParserVersionAndChangedConfigurationForceUnconditionalDownload() = runBlocking {
        val db = memory()
        try {
            var seen: HttpValidators? = null
            val repo = testApp(context, db) { url, _, validators -> seen = validators; body(url) }
            val id = repo.editor.seed(null, "合成", "https://example.org/a", "ja", "TSV", "AUTO")
            repo.fetch(id)
            db.library().deleteEntries(repo.database.library().source(id)!!.latestSnapshot!!)
            repo.fetch(id); assertNull(seen)
            db.openHelper.writableDatabase.execSQL("UPDATE snapshots SET validationVersion='obsolete' WHERE id=?", arrayOf(repo.database.library().source(id)!!.latestSnapshot))
            repo.fetch(id); assertNull(seen)
            repo.editor.seed(id, "合成", "https://example.org/a", "en", "TSV", "UTF-8")
            repo.fetch(id); assertNull(seen)
            repo.fetch(id); assertNotNull(seen)
        } finally { db.close() }
    }

    @Test fun invalidBodyValidatorIsNotPublishedAndOutdatedValidationCacheIsNotReused() = runBlocking {
        val db = memory()
        try {
            var text = "よみ\t合成語\t名詞"; var seen: HttpValidators? = null
            val repo = testApp(context, db) { url, _, validators -> seen = validators; body(url, text).copy(etag = if (text.startsWith("<html")) "\"bad\"" else "\"good\"") }
            val id = repo.editor.seed(null, "合成", "https://example.org/a", "ja", "TSV", "AUTO")
            repo.fetch(id); val first = repo.database.library().source(id)!!.latestSnapshot!!
            text = "<html>bad</html>"; repo.fetch(id)
            assertEquals(first, repo.database.library().source(id)!!.latestSnapshot); assertEquals(1, repo.database.library().snapshotIds(id).size)
            assertEquals("\"good\"", seen!!.etag); assertEquals("\"good\"", repo.database.library().snapshot(first)!!.etag)
            db.openHelper.writableDatabase.execSQL("UPDATE snapshots SET validationVersion='dictionary-validation-v0'") // Validated by older parser rules.
            text = "よみ\t合成語\t名詞"; repo.fetch(id); assertNull(seen)
        } finally { db.close() }
    }

    @Test fun failed304AckRollsBackSuccessTimeAndLeavesGenerationPending() = runBlocking {
        val db = memory()
        try {
            val repo = testApp(context, db) { url, _, validators -> if (validators == null) body(url) else unchanged(url) }
            val id = repo.editor.seed(null, "合成", "https://example.org/a", "ja", "TSV", "AUTO")
            repo.fetch(id); val before = repo.database.library().source(id)!!
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_http_ack BEFORE UPDATE OF completedGeneration ON pending_requests WHEN NEW.completedGeneration != OLD.completedGeneration BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            try { repo.fetch(id); fail() } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(before.fetchedAt, repo.database.library().source(id)!!.fetchedAt); assertEquals(1, db.requests().pending().size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_http_ack")
            repo.resumePendingFetches(); assertTrue(db.requests().pending().isEmpty())
        } finally { db.close() }
    }

    @Test fun contradictory304GetsOneUnconditionalRecoveryAndUnsolicited304CannotCreateCandidate() = runBlocking {
        val db = memory()
        try {
            var calls = 0
            val repo = testApp(context, db) { url, _, validators ->
                when (++calls) {
                    1 -> body(url)
                    2 -> { assertNotNull(validators); throw me.midra.dictionarysync.data.parser.DictionaryProblem("INVALID_304") }
                    else -> { assertNull(validators); body(url, "つぎ\t回復語\t名詞") }
                }
            }
            val id = repo.editor.seed(null, "合成", "https://example.org/a", "ja", "TSV", "AUTO")
            repo.fetch(id); val first = repo.database.library().source(id)!!.latestSnapshot
            repo.fetch(id); assertEquals(3, calls); assertNotEquals(first, repo.database.library().source(id)!!.latestSnapshot)
            val bad = testApp(context, db) { url, _, validators -> assertNull(validators); unchanged(url) }
            val badId = bad.editor.seed(null, "不正304", "https://example.org/b", "ja", "TSV", "AUTO")
            bad.fetch(badId); assertNull(bad.database.library().source(badId)!!.latestSnapshot)
            assertEquals("UNEXPECTED_304", bad.database.library().source(badId)!!.error)
        } finally { db.close() }
    }
}
