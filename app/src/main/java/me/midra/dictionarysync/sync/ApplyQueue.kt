package me.midra.dictionarysync.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.midra.dictionarysync.data.local.DictionaryDatabase
import me.midra.dictionarysync.data.local.SourceEntity

/** Sources whose desired shared-dictionary state is not in the Provider yet, applied one by one through [SourceSync]. */
class ApplyQueue(private val db: DictionaryDatabase, private val sync: SourceSync) {
    private val library = db.library()

    /** The sources the apply chain picks up by itself, an interrupted plan first (it blocks every other source). */
    suspend fun due(): List<SourceEntity> = library.sources().filter { it.syncState.dueInBackground }.sortedBy { it.syncState.waiting }

    suspend fun isEmpty() = due().isEmpty()

    /** Applies up to [limit] due sources, reporting each to [events]. A failure is recorded on its source and skipped. */
    suspend fun apply(limit: Int = Int.MAX_VALUE, events: SyncEvents = SyncEvents.None) {
        for (source in due().take(limit)) {
            currentCoroutineContext().ensureActive()
            events.applying(source)
            val before = committedPlans(source.id)
            val synced = sync.synchronize(source.id)
            val current = library.source(source.id) ?: source
            if (!synced) {
                events.applyFailed(current, current.syncError ?: "SYNC_FAILED")
                continue
            }
            // The journal holds exactly the Provider writes of a plan; borrowed words were never inserted or deleted.
            val plans = committedPlans(source.id) - before
            events.applied(current, plans.sumOf { db.sync().operationCount(it, OperationKind.INSERT) },
                plans.sumOf { db.sync().operationCount(it, OperationKind.DELETE) })
        }
    }

    /**
     * One pass of the apply chain. Returns true when sources are still due after a pass that changed something, so
     * another pass is useful; failed sources are not retried on their own.
     */
    suspend fun pass(events: SyncEvents = SyncEvents.None): Boolean {
        val before = due().map { it.id to it.syncState }
        apply(events = events)
        val after = due().map { it.id to it.syncState }
        return after.isNotEmpty() && after != before
    }

    private suspend fun committedPlans(id: String) =
        db.sync().plans().filter { it.sourceId == id && it.state == PlanState.COMMITTED }.map { it.id }.toSet()
}
