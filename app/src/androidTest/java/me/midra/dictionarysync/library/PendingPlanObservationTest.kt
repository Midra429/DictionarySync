package me.midra.dictionarysync.library

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.midra.dictionarysync.data.local.*
import me.midra.dictionarysync.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A single failed preview must not stop the unfinished-plan banner from updating afterwards. */
@RunWith(AndroidJUnit4::class)
class PendingPlanObservationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private object NoDispatch : FetchDispatch {
        override suspend fun enqueue() {}
        override suspend fun wakeAfter(delayMillis: Long) {}
        override suspend fun cancelWake() {}
        override suspend fun sources(sources: List<SourceEntity>) {}
    }

    @Test fun pendingKeepsObservingAfterAFailedPreview() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DictionaryDatabase::class.java).build()
        try {
            var failures = 1
            val sync = SyncRepository(context, db) {
                if (failures-- > 0) throw IllegalStateException("synthetic preview failure")
                SyncEngine(RoomSyncStore(db), RequirementsStorageTest.Gateway(), "pending-observation")
            }
            val model = LibraryViewModel(context.applicationContext as Application, SavedStateHandle(), LibraryRepository(context, db), sync, NoDispatch)
            val collector = launch(Dispatchers.Default) { model.pending.collect {} }
            db.library().addSource(SourceEntity("A", "合成", "https://example.org/a"))
            val plan = SyncPlan("P", "A", 1, null, null, "pending-observation", "fp", "token", createdAt = 1)
            db.sync().plan(plan)
            withTimeout(8_000) { while (model.error.value == null) delay(20) }
            assertEquals("IllegalStateException", model.error.value)
            // The insert may already have triggered a second, successful evaluation; either way a later change must be seen.
            db.sync().plan(plan.copy(createdAt = 2)) // any later change re-evaluates the plan
            withTimeout(10_000) { while (model.pending.value == null) delay(20) }
            assertEquals("P", model.pending.value!!.plan.id)
            collector.cancel()
        } finally { db.close() }
    }
}
