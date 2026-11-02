package me.midra.dictionarysync.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.probe.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.seed
import me.midra.dictionarysync.testApp

/** Isolated databases + fake Provider only. These tests do not write to UserDictionary. */
@RunWith(AndroidJUnit4::class)
class SyncStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun memory() = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
    private class Gateway : SyncGateway {
        val rows = linkedMapOf<Long, ProbeRow>()
        var id = 1L
        var failAfterInsert = false
        var inserts = 0
        override fun fingerprint() = "fake-provider"
        override fun checkAccess() = fingerprint() // No real Provider access; proof is supplied by the fixture.
        override fun find(key: SyncKey) = rows.values.filter { key.matches(it) }
        override fun byId(id: Long) = rows[id]
        override fun insert(value: ProbeValue): Long? {
            val row = value.row(id++); rows[row.id] = row; inserts++
            if (failAfterInsert) throw IllegalStateException("simulated process boundary")
            return row.id
        }
        override fun delete(row: ProbeRow): Int = if (rows[row.id] == row) { rows.remove(row.id); 1 } else 0
    }
    private suspend fun candidate(db: DictionaryDatabase, body: String = "よみ\t合成語\t名詞") : Pair<AppContainer, String> {
        val library = testApp(context, db) { url, _, _ -> val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain") }
        val source = library.editor.seed(null, "同期用合成", "https://example.org/?b=%2f&a=1&a=2", "ja-JP", "TSV", "UTF-8")
        library.fetcher.fetch(source)
        return library to source
    }
    @Test fun copiedSettingsRemainImmutableAndNewFetchInvalidatesUnapprovedPreview() = runBlocking {
        val db = memory()
        try {
            val (library, id) = candidate(db)
            val s = db.library().source(id)!!; val snap = db.library().snapshot(s.latestSnapshot!!)!!
            assertEquals("ja-JP", snap.sourceLocale); assertEquals("TSV", snap.sourceFormat); assertEquals(s.url, snap.sourceUrl)
            val engine = SyncEngine(RoomSyncStore(db), Gateway(), "install")
            val p = engine.prepare(id, s.revision, null, snap.id, snap.bodyHash, listOf(SyncKey.of(snap.sourceLocale!!, "合成語", "よみ")))
            try { library.editor.seed(id, "変更", s.url, "en", "TSV", "UTF-8"); fail() } catch (e: SyncStopped) { assertEquals("RECOVER_PLAN_FIRST", e.code) }
            library.fetcher.fetch(id)
            assertNotEquals(snap.id, db.library().source(id)!!.latestSnapshot)
            assertNotNull(db.library().snapshot(snap.id)) // The pinned candidate is never GC'd.
            try { engine.apply(p.plan.id, p.plan.token); fail() } catch (e: SyncStopped) { assertEquals("PLAN_STALE", e.code) }
            engine.cancel(p.plan.id)
            library.editor.seed(id, s.name, s.url, "en", "TSV", "UTF-8")
            assertEquals("ja-JP", db.library().snapshot(snap.id)!!.sourceLocale)
        } finally { db.close() }
    }
    @Test fun journalSurvivesDatabaseReopenAndUnknownInsertDoesNotGainOwnership() = runBlocking {
        val name = "sync-reopen-test"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, DictionaryDatabase::class.java, name).build()
        try {
            val (_, id) = candidate(db)
            val snap = db.library().source(id)!!.latestSnapshot!!
            val gateway = Gateway().apply { failAfterInsert = true }
            var engine = SyncEngine(RoomSyncStore(db), gateway, "install")
            val p = engine.prepare(id, 1, null, snap, "body", listOf(SyncKey.of("ja-JP", "合成語", "よみ")))
            try { engine.apply(p.plan.id, p.plan.token); fail() } catch (_: IllegalStateException) { }
            assertEquals(OperationPhase.IN_FLIGHT, db.sync().operations(p.plan.id).single().phase)
            assertNull(db.library().source(id)!!.activeSnapshot); assertTrue(db.sync().references().isEmpty())
            db.close(); db = Room.databaseBuilder(context, DictionaryDatabase::class.java, name).build()
            engine = SyncEngine(RoomSyncStore(db), gateway, "install")
            try { engine.apply(p.plan.id); fail() } catch (e: SyncStopped) { assertEquals("INSERT_UNKNOWN", e.code) }
            assertEquals(Ownership.UNKNOWN, db.sync().bindings().single().ownership); assertEquals(1, gateway.inserts)
            assertNull(db.library().source(id)!!.syncedAt)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun referencesAndSourceCommitTogetherAndDeletionKeepsHistory() = runBlocking {
        val db = memory()
        try {
            val (_, id) = candidate(db); val source = db.library().source(id)!!; val snap = source.latestSnapshot!!
            val gateway = Gateway(); val engine = SyncEngine(RoomSyncStore(db), gateway, "install")
            val key = SyncKey.of("ja-JP", "合成語", "よみ")
            val p = engine.prepare(id, 1, null, snap, "body", listOf(key)); engine.apply(p.plan.id, p.plan.token)
            assertEquals(snap, db.library().source(id)!!.activeSnapshot); assertEquals(snap, db.sync().references().single().snapshotId)
            assertEquals(OperationPhase.VERIFIED, db.sync().operations(p.plan.id).single().phase)
            val d = engine.prepare(id, 1, snap, null, "delete", emptyList()); engine.apply(d.plan.id, d.plan.token)
            assertTrue(gateway.rows.isEmpty()); assertTrue(db.sync().references().isEmpty()); assertTrue(db.library().sources().isEmpty())
            assertEquals(SourceLifecycle.DELETED, db.library().source(id)!!.lifecycle)
            assertNotNull(db.library().snapshot(snap)); assertEquals(2, db.sync().plans().size)
        } finally { db.close() }
    }
    @Test fun transactionFailureCannotPublishReferencesOrSuccessfulGeneration() = runBlocking {
        val db = memory()
        try {
            val (_, id) = candidate(db); val snap = db.library().source(id)!!.latestSnapshot!!
            val store = RoomSyncStore(db); val engine = SyncEngine(store, Gateway(), "install")
            val key = SyncKey.of("ja-JP", "合成語", "よみ"); val p = engine.prepare(id, 1, null, snap, "body", listOf(key))
            // Force failure after the reference insert, during source update, to test transaction rollback.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_sync BEFORE UPDATE OF activeSnapshot ON sources WHEN NEW.activeSnapshot IS NOT OLD.activeSnapshot BEGIN SELECT RAISE(ABORT, 'synthetic storage failure'); END")
            try { engine.apply(p.plan.id, p.plan.token); fail() } catch (_: android.database.SQLException) { }
            assertTrue(db.sync().references().isEmpty()); assertNull(db.library().source(id)!!.activeSnapshot)
            assertEquals(OperationPhase.VERIFIED, db.sync().operations(p.plan.id).single().phase)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_sync")
            engine.apply(p.plan.id)
            assertEquals(snap, db.library().source(id)!!.activeSnapshot); assertEquals(1, db.sync().references().size)
        } finally { db.close() }
    }
    @Test fun multipleSourceUnionRetainsFailedSourceAndReusesDeletedUrlSafely() = runBlocking {
        val db = memory()
        try {
            var aBody = "よみ\t共有の合成語\t名詞"
            var bBody = "よみ\t共有の合成語\t名詞\nべつ\tBの合成語\t名詞"
            val library = testApp(context, db) { url, _, _ ->
                val body = if (url.endsWith("a")) aBody else bBody
                val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val a = library.editor.seed(null, "A", "https://example.org/a", "ja", "TSV", "UTF-8")
            val b = library.editor.seed(null, "B", "https://example.org/b", "ja", "TSV", "UTF-8")
            val gateway = Gateway(); val engine = SyncEngine(RoomSyncStore(db), gateway, "install")
            suspend fun sync(id: String) {
                val source = db.library().source(id)!!; val snap = db.library().snapshot(source.latestSnapshot!!)!!
                val p = engine.prepare(id, source.revision, source.activeSnapshot, snap.id, snap.bodyHash,
                    db.library().entries(snap.id).map { SyncKey.of(it.locale, it.word, it.reading) })
                engine.apply(p.plan.id, p.plan.token)
            }
            library.fetcher.fetch(a); sync(a); library.fetcher.fetch(b); sync(b)
            assertEquals(2, gateway.inserts); assertEquals(3, db.sync().references().size)
            val oldA = db.library().source(a)!!.activeSnapshot
            aBody = "<html>failure</html>"; library.fetcher.fetch(a)
            assertEquals(oldA, db.library().source(a)!!.activeSnapshot)
            bBody = "べつ\tBの合成語\t名詞"; library.fetcher.fetch(b); sync(b)
            assertEquals(2, gateway.rows.size) // A's failed fetch still supplies its old reference.
            val deletion = engine.prepare(a, 1, oldA, null, "delete", emptyList())
            engine.apply(deletion.plan.id, deletion.plan.token)
            assertEquals(1, gateway.rows.size)
            val readded = library.editor.seed(null, "A再登録", "https://example.org/a", "ja", "TSV", "UTF-8")
            assertEquals(a, readded); assertEquals(2, db.library().source(readded)!!.revision)
            assertNull(db.library().source(readded)!!.activeSnapshot)
            assertEquals(4, db.sync().plans().size) // Tombstone reuse did not discard history.
        } finally { db.close() }
    }

}
