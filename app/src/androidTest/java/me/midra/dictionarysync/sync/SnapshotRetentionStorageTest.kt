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

/** Repeated fetch/apply cycles must not retain every generation a finished plan once referenced. Fake Provider only. */
@RunWith(AndroidJUnit4::class)
class SnapshotRetentionStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun repository(db: DictionaryDatabase): LibraryRepository {
        var fetches = 0
        return LibraryRepository(context, db) { url, _, _ ->
            // Alternate content, and no HTTP validators: every fetch yields a new generation.
            val bytes = "よみ\t保持試験${fetches++ % 2}\t名詞\nよみ\t共通語\t名詞".toByteArray()
            Download(bytes, Downloader.sha256(bytes), url, "text/plain")
        }
    }

    @Test fun finishedPlansDoNotPinGenerationsOrKeepTheirKeySets() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val gateway = RequirementsStorageTest.Gateway()
            val sync = SyncRepository(context, db) { SyncEngine(RoomSyncStore(db), gateway, "retention") }
            val repo = repository(db)
            val id = repo.save(null, "保持", "https://example.org/retention", "AUTO", "TSV", "AUTO")
            assertTrue(sync.synchronize(id))
            repeat(5) {
                repo.fetch(id)
                assertTrue(sync.synchronize(id))
            }
            val source = db.library().source(id)!!
            assertEquals("SYNCED", source.syncState)
            assertEquals(source.latestSnapshot, source.activeSnapshot)
            val kept = db.library().snapshotIds(id).toSet()
            // Latest (= applied) and the previous generation only.
            assertEquals(setOf(source.latestSnapshot, source.previousSnapshot), kept)
            val plans = db.sync().plans()
            assertEquals(6, plans.size) // Plan rows and their journal are retained.
            assertTrue(plans.all { it.state == "COMMITTED" })
            // Only the most recent committed plan (committed after the last fetch) still has its key set.
            assertEquals(listOf(plans.last().id), plans.filter { db.sync().planKeys(it.id).isNotEmpty() }.map { it.id })
            // Evicted generations have no orphaned entries.
            val allEntries = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM entries").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(kept.sumOf { db.library().entries(it!!).size }, allEntries)
        } finally { db.close() }
    }

    @Test fun unfinishedPlanStillPinsItsGenerationsAndKeys() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val gateway = RequirementsStorageTest.Gateway()
            val sync = SyncRepository(context, db) { SyncEngine(RoomSyncStore(db), gateway, "retention") }
            val repo = repository(db)
            val id = repo.save(null, "保持", "https://example.org/retention", "AUTO", "TSV", "AUTO")
            assertTrue(sync.synchronize(id))
            val pinned = db.library().source(id)!!.latestSnapshot!!
            val pending = SyncPlan("PENDING", id, 1, pinned, pinned, "retention", "fp", "token", true, "RECOVERY_REQUIRED", createdAt = Long.MAX_VALUE)
            db.sync().plan(pending); db.sync().planKeys(listOf(PlanKey("PENDING", "k", true)))
            repeat(3) { repo.fetch(id) }
            assertTrue(pinned in db.library().snapshotIds(id))
            assertTrue(db.library().entries(pinned).isNotEmpty())
            assertEquals(1, db.sync().planKeys("PENDING").size)
        } finally { db.close() }
    }
}
