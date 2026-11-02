package me.midra.dictionarysync.library

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.data.source.*
import me.midra.dictionarysync.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import me.midra.dictionarysync.testApp
import me.midra.dictionarysync.ui.AppViewModel

/** Cancelling the initial fetch of an add/edit discards the candidate; nothing is registered and the draft stays open. */
@RunWith(AndroidJUnit4::class)
class SaveCancellationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private object NoDispatch : FetchDispatch {
        override suspend fun enqueue() {}
        override suspend fun wakeAfter(delayMillis: Long) {}
        override suspend fun cancelWake() {}
        override suspend fun sources(sources: List<SourceEntity>) {}
    }

    @Test fun cancellingAnAddInProgressSavesNothingAndKeepsTheForm() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        val store = ViewModelStore()
        val started = CountDownLatch(1)
        val stalled = CountDownLatch(1)
        try {
            val repo = testApp(context, db) { url, _, _ ->
                started.countDown()
                stalled.await(30, TimeUnit.SECONDS) // A stalled server; only an interrupt ends it early.
                val bytes = "よみ\t遅延語\t名詞".toByteArray()
                Download(bytes, Downloader.sha256(bytes), url, "text/plain")
            }
            val model = AppViewModel(context.applicationContext as Application, SavedStateHandle(), repo)
            store.put("cancel", model)
            withTimeout(10_000) { while (model.operations.busy.value) delay(20) }
            model.form.edit(null)
            model.form.save(SourceDraft("取消", "https://example.org/cancel", "AUTO", "AUTO", "AUTO", 24))
            assertTrue(started.await(10, TimeUnit.SECONDS))
            model.form.cancel()
            withTimeout(5_000) { while (model.operations.busy.value) delay(20) }
            assertTrue(db.library().sources().isEmpty())
            assertTrue(db.requests().pending().isEmpty())
            assertNull(model.operations.error.value)
            assertTrue(model.form.open.value)
        } finally {
            stalled.countDown()
            store.clear()
            db.close()
        }
    }
}
