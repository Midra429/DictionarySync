package me.midra.dictionarysync.ui.sources

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.ui.common.Operations

enum class BatchOperation { ENABLE, DISABLE, DELETE }

/** The outcome of a batch, shown once. Failed sources stay selected. */
data class BatchResult(val operation: BatchOperation, val succeeded: Int, val failed: Int)

/**
 * The source list: sources with their counts and pending fetches, the multi-selection, and the per-source and batch
 * operations. Screens only store desired states here; the background chains do the fetching and Provider writes.
 */
class SourceListState(
    scope: CoroutineScope,
    private val saved: SavedStateHandle,
    private val app: AppContainer,
    private val operations: Operations,
    /** Called with the sources a batch deleted. */
    private val onDeleted: (Set<String>) -> Unit,
) {
    private val dao = app.database.library()
    private val sharing = SharingStarted.WhileSubscribed(5000)

    /** null until the first database emission, so the UI can tell "loading" from "no sources". */
    val loaded: StateFlow<List<SourceEntity>?> = dao.observeSources().stateIn(scope, sharing, null)
    val sources: StateFlow<List<SourceEntity>> = loaded.map { it.orEmpty() }.stateIn(scope, sharing, emptyList())
    val counts = dao.observeCounts().stateIn(scope, sharing, emptyList())
    val requests = app.database.requests().observePending().stateIn(scope, sharing, emptyList())
    val selection: StateFlow<List<String>> = saved.getStateFlow(SELECTION, arrayListOf<String>())
    private val result = MutableStateFlow<BatchResult?>(null)
    /** Result of the last batch operation, shown once by the UI. */
    val notice: StateFlow<BatchResult?> = result.asStateFlow()

    fun consumeNotice() { result.value = null }
    fun toggleSelection(id: String) { select(selection.value.toMutableList().apply { if (!remove(id)) add(id) }) }
    fun selectAll() = select(sources.value.map { it.id })
    fun clearSelection() = select(emptyList())
    private fun select(ids: List<String>) { saved[SELECTION] = ArrayList(ids) }

    fun fetch(id: String) = operations.run { app.scheduler.requestOne(id) }
    fun fetchAll() = operations.run { app.scheduler.requestAll() }
    /** Re-queues a source whose shared-dictionary write failed (for example after the diagnosis was completed). */
    fun retry(id: String) = operations.run { app.sourceSync.retry(id); app.scheduler.repair(userRequested = true) }
    fun setEnabled(id: String, value: Boolean) = batch(listOf(id), if (value) BatchOperation.ENABLE else BatchOperation.DISABLE)

    /** Applies [operation] to each source in turn; one failure does not undo the others. */
    fun batch(ids: List<String>, operation: BatchOperation) = operations.run {
        val targets = ids.distinct()
        val failed = arrayListOf<String>()
        for (id in targets) {
            try {
                val accepted = when (operation) {
                    BatchOperation.DELETE -> app.sourceSync.delete(id, background = true)
                    else -> app.sourceSync.setEnabled(id, operation == BatchOperation.ENABLE, background = true)
                }
                if (!accepted) failed += id
                // A source enabled after its interval passed is fetched now rather than a full interval later.
                if (operation == BatchOperation.ENABLE && dao.source(id)?.enabled == true) app.scheduler.requestPeriodic(app.now(), id)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failed += id
                operations.report(failure)
            }
        }
        select(failed)
        result.value = BatchResult(operation, targets.size - failed.size, failed.size)
        if (operation == BatchOperation.DELETE) onDeleted(targets.toSet() - failed.toSet())
        app.scheduler.repair(userRequested = true)
    }

    private companion object { const val SELECTION = "selection" }
}
