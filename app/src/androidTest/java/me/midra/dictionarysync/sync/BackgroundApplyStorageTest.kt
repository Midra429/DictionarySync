package me.midra.dictionarysync.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.library.RequirementsStorageTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.save
import me.midra.dictionarysync.testApp
import me.midra.dictionarysync.fetch

/** Screen actions store the desired state; the apply chain performs the Provider writes. Fake Provider only. */
@RunWith(AndroidJUnit4::class)
class BackgroundApplyStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Switchable(val delegate: RequirementsStorageTest.Gateway) : SyncGateway by delegate {
        var access = true
        override fun checkAccess(): String { if (!access) throw SyncStopped("DIAGNOSIS_REQUIRED"); return delegate.checkAccess() }
    }

    private class Fixture(context: android.content.Context) {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val fake = RequirementsStorageTest.Gateway()
        val gateway = Switchable(fake)
        val sync = SourceSync(context, db) { SyncEngine(RoomSyncStore(db), gateway, "background-apply") }
        val repo = testApp(context, db, sync = sync) { url, _, _ ->
            val bytes = "よみ\t背景語\t名詞".toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
        }
        val runner = repo.applies
        suspend fun source(id: String) = db.library().source(id)!!
    }

    @Test fun desiredStatesAreQueuedAndTheApplyChainWritesThem() = runBlocking {
        val f = Fixture(context)
        try {
            val id = f.repo.editor.save(null, "背景", "https://example.org/background", "AUTO", "TSV", "AUTO")
            assertEquals(SyncState.UPDATE_PENDING, f.source(id).syncState); assertEquals(0, f.fake.inserts)
            f.runner.pass()
            assertEquals(SyncState.SYNCED, f.source(id).syncState); assertEquals(1, f.fake.rows.size)

            assertTrue(f.sync.setEnabled(id, false, background = true))
            assertEquals(SyncState.REMOVE_PENDING, f.source(id).syncState); assertEquals(1, f.fake.rows.size) // nothing written yet
            f.runner.pass()
            assertEquals(SyncState.REMOVED, f.source(id).syncState); assertTrue(f.fake.rows.isEmpty())

            assertTrue(f.sync.setEnabled(id, true, background = true)); f.runner.pass()
            assertTrue(f.sync.delete(id, background = true))
            assertEquals(SourceLifecycle.DELETE_PENDING, f.source(id).lifecycle); assertEquals(1, f.fake.rows.size)
            f.runner.pass()
            assertEquals(SourceLifecycle.DELETED, f.source(id).lifecycle); assertTrue(f.fake.rows.isEmpty())
        } finally { f.db.close() }
    }

    private class Recorder : SyncEvents {
        val log = mutableListOf<String>()
        override fun applying(source: SourceEntity) { log += "applying ${source.name}" }
        override fun applied(source: SourceEntity, added: Int, removed: Int) { log += "applied ${source.name} +$added -$removed" }
        override fun applyFailed(source: SourceEntity, code: String) { log += "failed ${source.name} $code" }
    }

    @Test fun eachApplyReportsTheWordsItWroteOrWhyItFailed() = runBlocking {
        val f = Fixture(context)
        val events = Recorder()
        try {
            val id = f.repo.editor.save(null, "通知", "https://example.org/notify", "AUTO", "TSV", "AUTO")
            f.runner.pass(events)
            f.repo.fetch(id) // Same content: the apply finds nothing to write.
            f.runner.pass(events)
            assertTrue(f.sync.setEnabled(id, false, background = true)); f.runner.pass(events)
            assertTrue(f.sync.setEnabled(id, true, background = true))
            f.gateway.access = false
            f.runner.pass(events)
            assertEquals(listOf("applying 通知", "applied 通知 +1 -0", "applying 通知", "applied 通知 +0 -0",
                "applying 通知", "applied 通知 +0 -1", "applying 通知", "failed 通知 DIAGNOSIS_REQUIRED"), events.log)
        } finally { f.db.close() }
    }

    @Test fun releasingSomethingNeverAppliedCompletesWithoutTheProvider() = runBlocking {
        val f = Fixture(context)
        try {
            val a = f.repo.editor.save(null, "未反映A", "https://example.org/a", "AUTO", "TSV", "AUTO")
            val b = f.repo.editor.save(null, "未反映B", "https://example.org/b", "AUTO", "TSV", "AUTO")
            f.gateway.access = false // Any Provider access would fail.
            assertTrue(f.sync.setEnabled(a, false, background = true))
            assertEquals(SyncState.REMOVED, f.source(a).syncState)
            assertTrue(f.sync.delete(b, background = true))
            assertEquals(SourceLifecycle.DELETED, f.source(b).lifecycle)
            assertEquals(0, f.fake.inserts)
        } finally { f.db.close() }
    }

    @Test fun aFailedWriteCanBeRetriedAfterItsCauseIsFixed() = runBlocking {
        val f = Fixture(context)
        try {
            val id = f.repo.editor.save(null, "再試行", "https://example.org/retry", "AUTO", "TSV", "AUTO")
            f.gateway.access = false
            f.runner.pass()
            assertEquals(SyncState.SYNC_FAILED, f.source(id).syncState); assertEquals("DIAGNOSIS_REQUIRED", f.source(id).syncError)
            assertFalse(f.runner.pass()) // Failed sources are not retried on their own.
            f.gateway.access = true
            f.sync.retry(id)
            assertEquals(SyncState.UPDATE_PENDING, f.source(id).syncState); assertNull(f.source(id).syncError)
            f.runner.pass()
            assertEquals(SyncState.SYNCED, f.source(id).syncState); assertEquals(1, f.fake.rows.size)
        } finally { f.db.close() }
    }
}
