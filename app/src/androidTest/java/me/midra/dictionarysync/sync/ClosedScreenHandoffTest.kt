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
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.ui.AppViewModel
import me.midra.dictionarysync.library.RequirementsStorageTest
import me.midra.dictionarysync.probe.ProbeRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.save
import me.midra.dictionarysync.testApp
import me.midra.dictionarysync.ui.sources.BatchOperation

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
    private fun repo(db: DictionaryDatabase, sync: SourceSync, dispatch: FetchDispatch) = testApp(context, db, sync = sync, dispatch = dispatch) { url, _, _ ->
        val bytes = "よみ\t引継語\t名詞".toByteArray(); Download(bytes, Downloader.sha256(bytes), url, "text/plain")
    }

    @Test fun enablingFromTheScreenOnlyQueuesTheWrite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        try {
            val gateway = Blocking(RequirementsStorageTest.Gateway())
            val dispatch = CountingDispatch()
            val repo = repo(db, SourceSync(context, db) { SyncEngine(RoomSyncStore(db), gateway, "handoff") }, dispatch)
            val id = repo.editor.save(null, "引継", "https://example.org/handoff", "AUTO", "TSV", "AUTO")
            db.library().updateSource(db.library().source(id)!!.copy(enabled = false, syncState = SyncState.REMOVED))
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("handoff", model)
            withTimeout(10_000) { while (model.operations.busy.value) delay(20) }
            dispatch.applies.set(0)
            model.sources.batch(listOf(id), BatchOperation.ENABLE)
            withTimeout(10_000) { while (model.sources.notice.value == null) delay(20) }
            assertEquals(SyncState.UPDATE_PENDING, db.library().source(id)!!.syncState)
            assertEquals(0, gateway.delegate.inserts)
            assertTrue(dispatch.applies.get() > 0)
        } finally { store.clear(); db.close() }
    }

    @Test fun reviewedWriteInterruptedByAClosedScreenIsQueuedForTheWorker() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        val gateway = Blocking(RequirementsStorageTest.Gateway())
        try {
            val sync = SourceSync(context, db) { SyncEngine(RoomSyncStore(db), gateway, "handoff") }
            val dispatch = CountingDispatch()
            val repo = repo(db, sync, dispatch)
            val id = repo.editor.save(null, "引継", "https://example.org/handoff", "AUTO", "TSV", "AUTO")
            sync.prepare(id) // An unfinished, not yet approved plan: reviewed and applied from the screen.
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("handoff", model)
            val collector = launch(Dispatchers.Default) { model.review.pending.collect {} }
            withTimeout(10_000) { while (model.operations.busy.value || model.review.pending.value == null) delay(20) }
            dispatch.applies.set(0)
            gateway.block = true
            model.review.open(); model.review.apply()
            assertTrue(gateway.entered.await(10, TimeUnit.SECONDS))
            collector.cancel()
            store.clear() // The screen is closed: viewModelScope is cancelled mid-write.
            gateway.release.countDown()
            withTimeout(10_000) { while (dispatch.applies.get() == 0) delay(20) }
            assertTrue(db.library().source(id)!!.syncState.dueInBackground)
        } finally {
            gateway.release.countDown()
            db.close()
        }
    }
}
