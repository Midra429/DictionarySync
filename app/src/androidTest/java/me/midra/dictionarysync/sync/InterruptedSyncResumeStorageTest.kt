package me.midra.dictionarysync.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.library.LibraryRepository
import me.midra.dictionarysync.library.RequirementsStorageTest
import me.midra.dictionarysync.probe.ProbeRow
import me.midra.dictionarysync.probe.ProbeValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** An ordinary apply cancelled mid-way (Worker stop, closed screen) is resumed by the next background pass. Fake Provider only. */
@RunWith(AndroidJUnit4::class)
class InterruptedSyncResumeStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Cancels once, at a read before the second key's insert is journaled. */
    private class Interrupting(val delegate: RequirementsStorageTest.Gateway) : SyncGateway by delegate {
        var interrupt = true
        override fun findAll(keys: Collection<SyncKey>): Map<String, List<ProbeRow>> {
            if (interrupt && delegate.inserts == 1) { interrupt = false; throw CancellationException("synthetic stop") }
            return delegate.findAll(keys)
        }
        override fun insert(value: ProbeValue): Long? = delegate.insert(value)
    }

    @Test fun cancelledApplyIsResumedWithoutRepeatingInserts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val fake = RequirementsStorageTest.Gateway()
            val gateway = Interrupting(fake)
            // One key per batch, so the search before the second key's batch follows the first insert.
            val sync = SyncRepository(context, db) { SyncEngine(RoomSyncStore(db), gateway, "resume", batch = 1) }
            val repo = LibraryRepository(context, db, synchronizeSource = { sync.synchronize(it) }) { url, _, _ ->
                val bytes = "よみ\t再開一\t名詞\nよみ\t再開二\t名詞\nよみ\t再開三\t名詞".toByteArray()
                Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val id = repo.save(null, "再開", "https://example.org/resume", "AUTO", "TSV", "AUTO")
            try { sync.synchronize(id); fail("expected cancellation") } catch (_: CancellationException) { }
            val interrupted = db.library().source(id)!!.syncState
            assertTrue(interrupted, interrupted in setOf("ADDING", "PREPARED", "REMOVING"))
            assertEquals(1, fake.inserts)

            val delay = FetchRunner(repo).runOne()
            assertEquals("SYNCED", db.library().source(id)!!.syncState)
            assertEquals(3, fake.inserts) // the first insert is reconciled from the journal, not repeated
            assertEquals(3, fake.rows.size)
            assertTrue(db.sync().plans().all { it.state == "COMMITTED" })
            assertNull(delay)
        } finally { db.close() }
    }

    @Test fun recoveryRequiredPlanIsNotResumedAutomatically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            val fake = RequirementsStorageTest.Gateway().apply { unknownInsertAt = 1 }
            val sync = SyncRepository(context, db) { SyncEngine(RoomSyncStore(db), fake, "resume") }
            val repo = LibraryRepository(context, db, synchronizeSource = { sync.synchronize(it) }) { url, _, _ ->
                val bytes = "よみ\t不明語\t名詞".toByteArray()
                Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val id = repo.save(null, "不明", "https://example.org/unknown", "AUTO", "TSV", "AUTO")
            assertFalse(sync.synchronize(id))
            assertEquals("RECOVERY_REQUIRED", db.library().source(id)!!.syncState)
            FetchRunner(repo).runOne()
            assertEquals(1, fake.inserts) // explicit recovery stays a user decision
            assertEquals("RECOVERY_REQUIRED", db.library().source(id)!!.syncState)
        } finally { db.close() }
    }
}
