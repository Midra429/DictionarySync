package me.midra.dictionarysync.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.library.LibraryRepository
import me.midra.dictionarysync.library.RequirementsStorageTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A consented recovery stops if source references change afterwards, by any writer. Fake Provider only. */
@RunWith(AndroidJUnit4::class)
class RecoveryReferenceVersionStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun failedPlan(db: DictionaryDatabase, gateway: RequirementsStorageTest.Gateway, sync: SyncRepository): SyncPlan {
        var body = "よみ\t参照語\t名詞"
        val repo = LibraryRepository(context, db) { url, _, _ -> body.toByteArray().let { Download(it, Downloader.sha256(it), url, "text/plain") } }
        val id = repo.save(null, "参照", "https://example.org/reference", "AUTO", "TSV", "AUTO")
        assertTrue(sync.synchronize(id))
        gateway.rows.clear() // Externally deleted in Settings.
        body = "よみ\t参照語\t名詞\nよみ\t参照語二\t名詞"
        repo.fetch(id)
        assertFalse(sync.synchronize(id))
        return db.sync().plans().single { it.state == "RECOVERY_REQUIRED" }
    }

    @Test fun referenceChangeByAnyWriterInvalidatesConsentBeforeMutation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val gateway = RequirementsStorageTest.Gateway()
            val sync = SyncRepository(context, db) { SyncEngine(RoomSyncStore(db), gateway, "reference-version") }
            val parent = failedPlan(db, gateway, sync)
            val recovery = sync.prepareRecovery(parent.id, "RESYNC")
            val inserts = gateway.inserts
            val key = db.sync().keys().first().id
            db.openHelper.writableDatabase.execSQL("INSERT INTO source_references VALUES ('elsewhere','snapshot',?)", arrayOf(key))
            try { sync.apply(recovery.plan.id, recovery.plan.token); fail("expected RECOVERY_STALE") }
            catch (e: SyncStopped) { assertEquals("RECOVERY_STALE", e.code) }
            assertEquals(inserts, gateway.inserts)
        } finally { db.close() }
    }

    @Test fun unchangedReferencesLetTheConsentedRecoveryFinish() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val gateway = RequirementsStorageTest.Gateway()
            val sync = SyncRepository(context, db) { SyncEngine(RoomSyncStore(db), gateway, "reference-version") }
            val parent = failedPlan(db, gateway, sync)
            val recovery = sync.prepareRecovery(parent.id, "RESYNC")
            sync.apply(recovery.plan.id, recovery.plan.token)
            assertTrue(db.sync().plans().all { it.state in SyncEngine.TERMINAL })
            assertEquals(setOf("参照語", "参照語二"), gateway.rows.values.map { it.word }.toSet())
        } finally { db.close() }
    }
}
