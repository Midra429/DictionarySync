package me.midra.dictionarysync.sync

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.library.LibraryRepository
import me.midra.dictionarysync.library.LibraryViewModel
import me.midra.dictionarysync.library.RequirementsStorageTest
import me.midra.dictionarysync.probe.ProbeRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Screen actions do not write the Provider themselves; a write still run from the screen is handed off if the screen goes away. */
@RunWith(AndroidJUnit4::class)
class ClosedScreenHandoffTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class CountingDispatch : FetchDispatch {
        val applies = AtomicInteger()
        override suspend fun enqueue() {}
        override suspend fun applyNow() { applies.incrementAndGet() }
        override suspend fun wakeAfter(delayMillis: Long) {}
        override suspend fun cancelWake() {}
        override suspend fun sources(sources: List<SourceEntity>) {}
    }
    private class Blocking(val delegate: RequirementsStorageTest.Gateway) : SyncGateway by delegate {
        var block = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun findAll(keys: Collection<SyncKey>): Map<String, List<ProbeRow>> {
            if (block) { entered.countDown(); release.await(20, TimeUnit.SECONDS) }
            return delegate.findAll(keys)
        }
    }
    private fun repo(db: DictionaryDatabase) = LibraryRepository(context, db) { url, _, _ ->
        val bytes = "よみ\t引継語\t名詞".toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
    }

    @Test fun enablingFromTheScreenOnlyQueuesTheWrite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val gateway = Blocking(RequirementsStorageTest.Gateway())
            val repo = repo(db)
            val id = repo.save(null, "引継", "https://example.org/handoff", "AUTO", "TSV", "AUTO")
            db.library().updateSource(db.library().source(id)!!.copy(enabled = false, syncState = "REMOVED"))
            val dispatch = CountingDispatch()
            val model = LibraryViewModel(context.applicationContext as Application, SavedStateHandle(), repo,
                SyncRepository(context, db) { SyncEngine(RoomSyncStore(db), gateway, "handoff") }, dispatch)
            store.put("handoff", model)
            withTimeout(10_000) { while (model.busy.value) delay(20) }
            dispatch.applies.set(0)
            model.batch(listOf(id), "ENABLE")
            withTimeout(10_000) { while (model.notice.value == null) delay(20) }
            assertEquals("UPDATE_PENDING", db.library().source(id)!!.syncState)
            assertEquals(0, gateway.delegate.inserts)
            assertTrue(dispatch.applies.get() > 0)
        } finally { store.clear(); db.close() }
    }

    @Test fun reviewedWriteInterruptedByAClosedScreenIsQueuedForTheWorker() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        val gateway = Blocking(RequirementsStorageTest.Gateway())
        try {
            val repo = repo(db)
            val sync = SyncRepository(context, db) { SyncEngine(RoomSyncStore(db), gateway, "handoff") }
            val id = repo.save(null, "引継", "https://example.org/handoff", "AUTO", "TSV", "AUTO")
            sync.prepare(id) // An unfinished, not yet approved plan: reviewed and applied from the screen.
            val dispatch = CountingDispatch()
            val model = LibraryViewModel(context.applicationContext as Application, SavedStateHandle(), repo, sync, dispatch)
            store.put("handoff", model)
            val collector = launch(Dispatchers.Default) { model.pending.collect {} }
            withTimeout(10_000) { while (model.busy.value || model.pending.value == null) delay(20) }
            dispatch.applies.set(0)
            gateway.block = true
            model.resumeSync(); model.applySync()
            assertTrue(gateway.entered.await(10, TimeUnit.SECONDS))
            collector.cancel()
            store.clear() // The screen is closed: viewModelScope is cancelled mid-write.
            gateway.release.countDown()
            withTimeout(10_000) { while (dispatch.applies.get() == 0) delay(20) }
            assertTrue(db.library().source(id)!!.syncState in LibraryRepository.BACKGROUND_SYNC_STATES)
        } finally {
            gateway.release.countDown()
            db.close()
        }
    }
}
