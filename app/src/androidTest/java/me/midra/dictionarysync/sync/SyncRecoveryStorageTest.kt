package me.midra.dictionarysync.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.library.LibraryRepository
import me.midra.dictionarysync.probe.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated synthetic databases and an in-memory gateway. Never accesses the shared UserDictionary. */
@RunWith(AndroidJUnit4::class)
class SyncRecoveryStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val a = SyncKey.of("ja", "復旧合成A", "よみ")
    private val b = SyncKey.of("ja", "復旧合成B", "よみ")
    private class Gateway : SyncGateway {
        val rows = linkedMapOf<Long, ProbeRow>()
        var id = 1L
        var inserts = 0
        var deletes = 0
        override fun fingerprint() = "synthetic-recovery"
        override fun checkAccess() = fingerprint()
        override fun find(key: SyncKey) = rows.values.filter { key.matches(it) }
        override fun byId(id: Long) = rows[id]
        override fun insert(value: ProbeValue): Long { val row = value.row(id++); rows[row.id] = row; inserts++; return row.id }
        override fun delete(row: ProbeRow): Int {
            if (rows[row.id] != row) return 0
            rows.remove(row.id); deletes++; return 1
        }
    }
    private inner class Fixture(val db: DictionaryDatabase, val gateway: Gateway = Gateway()) {
        var body = "よみ\t復旧合成A\t名詞"
        val library = LibraryRepository(context, db) { url, _, _ ->
            val bytes = body.toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
        }
        fun engine() = SyncEngine(RoomSyncStore(db), gateway, "recovery-install")
        suspend fun source(name: String = "A"): String {
            val id = library.seedSource(null, name, "https://example.org/recovery/$name", "ja", "TSV", "UTF-8")
            library.fetch(id); return id
        }
        suspend fun plan(id: String, deleting: Boolean = false): SyncPreview {
            val source = db.library().source(id)!!
            val snapshot = if (deleting) null else db.library().snapshot(source.latestSnapshot!!)!!
            return engine().prepare(id, source.revision, source.activeSnapshot, snapshot?.id, snapshot?.bodyHash ?: "delete",
                snapshot?.let { db.library().entries(it.id).map { e -> SyncKey.of(e.locale, e.word, e.reading) } }.orEmpty())
        }
        suspend fun apply(p: SyncPreview) = engine().apply(p.plan.id, p.plan.token)
        fun blockCommit() = db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_recovery_commit BEFORE UPDATE OF activeSnapshot ON sources WHEN NEW.activeSnapshot IS NOT OLD.activeSnapshot BEGIN SELECT RAISE(ABORT, 'synthetic commit failure'); END")
    }
    @Test fun compensationSurvivesReopenAndPreservesOldGenerationAndSuccessTime(): Unit = runBlocking {
        val name = "sync-recovery-reopen"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, DictionaryDatabase::class.java, name).build()
        try {
            val f = Fixture(db); val id = f.source(); f.apply(f.plan(id))
            val before = db.library().source(id)!!; val refs = db.sync().references()
            f.body = "よみ\t復旧合成B\t名詞"; f.library.fetch(id)
            val p = f.plan(id); f.blockCommit()
            try { f.apply(p); fail() } catch (_: android.database.SQLException) { }
            val parentOps = db.sync().operations(p.plan.id)
            val r = f.engine().prepareRecovery(p.plan.id, "COMPENSATE")
            db.close(); db = Room.databaseBuilder(context, DictionaryDatabase::class.java, name).build()
            val reopened = Fixture(db, f.gateway)
            reopened.engine().apply(r.plan.id, r.plan.token)
            val after = db.library().source(id)!!
            assertEquals(before.activeSnapshot, after.activeSnapshot); assertEquals(before.syncedAt, after.syncedAt)
            assertEquals("FAILED_PRESERVED", after.syncState); assertEquals("ACTIVE", after.lifecycle)
            assertEquals(refs, db.sync().references()); assertEquals(parentOps, db.sync().operations(p.plan.id))
            assertEquals(1, f.gateway.rows.size); assertTrue(a.matches(f.gateway.rows.values.single()))
            assertEquals("FAILED_PRESERVED", db.sync().plans().single { it.id == p.plan.id }.state)
            assertEquals("COMMITTED", db.sync().plans().single { it.id == r.plan.id }.state)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun resyncUsesPinnedCandidateAndCommitsParentAndChildAtomically(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val f = Fixture(db); val aId = f.source(); f.apply(f.plan(aId))
            val bId = f.source("B"); f.apply(f.plan(bId))
            val original = f.gateway.rows.values.single(); val edited = original.copy(word = "手動編集後")
            f.gateway.rows[original.id] = edited
            f.body = "よみ\t復旧合成A\t名詞\nよみ\t復旧合成B\t名詞"; f.library.fetch(aId)
            val p = f.plan(aId)
            try { f.apply(p); fail() } catch (e: SyncStopped) { assertEquals("EXTERNAL_EDIT", e.code) }
            val r = f.engine().prepareRecovery(p.plan.id, "RESYNC")
            assertEquals(setOf(aId, bId), r.relatedSources.toSet())
            f.body = "べつ\t未承認の新版\t名詞"; f.library.fetch(aId)
            f.blockCommit()
            try { f.apply(r); fail() } catch (_: android.database.SQLException) { }
            assertEquals(3, f.gateway.inserts); assertEquals(edited, f.gateway.rows[original.id])
            assertNotEquals(p.plan.newSnapshot, db.library().source(aId)!!.activeSnapshot)
            assertEquals("RECOVERING", db.sync().plans().single { it.id == p.plan.id }.state)
            assertEquals("RECOVERY_REQUIRED", db.sync().plans().single { it.id == r.plan.id }.state)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_recovery_commit")
            f.engine().apply(r.plan.id)
            assertEquals(3, f.gateway.inserts)
            assertEquals(p.plan.newSnapshot, db.library().source(aId)!!.activeSnapshot)
            assertNotEquals(p.plan.newSnapshot, db.library().source(aId)!!.latestSnapshot)
            assertEquals("COMMITTED", db.sync().plans().single { it.id == p.plan.id }.state)
            assertEquals("COMMITTED", db.sync().plans().single { it.id == r.plan.id }.state)
            assertEquals(3, db.sync().references().size)
        } finally { db.close() }
    }
    @Test fun compensationCommitFailureRetainsPendingPlanAndDoesNotRepeatRestoration(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val f = Fixture(db); val id = f.source(); f.apply(f.plan(id))
            val before = db.library().source(id)!!
            val p = f.plan(id, deleting = true); f.blockCommit()
            try { f.apply(p); fail() } catch (_: android.database.SQLException) { }
            assertEquals("DELETE_PENDING", db.library().source(id)!!.lifecycle)
            val r = f.engine().prepareRecovery(p.plan.id, "COMPENSATE")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_compensated BEFORE UPDATE ON sources WHEN NEW.syncState='FAILED_PRESERVED' BEGIN SELECT RAISE(ABORT, 'synthetic compensation commit failure'); END")
            try { f.apply(r); fail() } catch (_: android.database.SQLException) { }
            assertEquals(before.activeSnapshot, db.library().source(id)!!.activeSnapshot)
            assertEquals("DELETE_PENDING", db.library().source(id)!!.lifecycle)
            assertEquals("RECOVERING", db.sync().plans().single { it.id == p.plan.id }.state)
            assertEquals("RECOVERY_REQUIRED", db.sync().plans().single { it.id == r.plan.id }.state)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_compensated")
            f.engine().apply(r.plan.id)
            assertEquals(2, f.gateway.inserts); assertEquals(1, f.gateway.deletes)
            assertEquals(before.syncedAt, db.library().source(id)!!.syncedAt)
            assertEquals("ACTIVE", db.library().source(id)!!.lifecycle)
            assertEquals("FAILED_PRESERVED", db.library().source(id)!!.syncState)
        } finally { db.close() }
    }
    @Test fun approvalAndConflictReleaseRollbackTogetherBeforeAnyProviderWrite(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val f = Fixture(db); val id = f.source(); f.apply(f.plan(id)); f.gateway.rows.clear()
            val p = f.plan(id)
            try { f.apply(p); fail() } catch (e: SyncStopped) { assertEquals("EXTERNAL_DELETE", e.code) }
            val r = f.engine().prepareRecovery(p.plan.id, "RESYNC")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_release BEFORE UPDATE ON provider_bindings WHEN OLD.conflict IS NOT NULL AND NEW.conflict IS NULL BEGIN SELECT RAISE(ABORT, 'synthetic activation failure'); END")
            // Bindings are written with INSERT OR REPLACE; the old row is still visible to a BEFORE INSERT trigger.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_release_replace BEFORE INSERT ON provider_bindings WHEN NEW.conflict IS NULL AND EXISTS (SELECT 1 FROM provider_bindings WHERE keyId = NEW.keyId AND conflict IS NOT NULL) BEGIN SELECT RAISE(ABORT, 'synthetic activation failure'); END")
            try { f.apply(r); fail() } catch (_: android.database.SQLException) { }
            assertFalse(db.sync().plans().single { it.id == r.plan.id }.approved)
            assertEquals("EXTERNAL_DELETE", db.sync().bindings().single().conflict)
            assertEquals(1, f.gateway.inserts)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_release")
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_release_replace")
            f.apply(r); assertEquals(2, f.gateway.inserts)
        } finally { db.close() }
    }
}
