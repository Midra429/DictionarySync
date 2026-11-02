package me.midra.dictionarysync.ui.editor

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.HttpRetry
import me.midra.dictionarysync.data.source.SourceRules
import me.midra.dictionarysync.library.SourceDraft
import me.midra.dictionarysync.ui.common.Operations

/**
 * The add/edit dialog. The draft itself lives in the dialog; this keeps what must survive recreation and finish even
 * if the dialog is gone: whether it is open, what it edits, the running save and a server's Retry-After.
 */
class SourceFormState(private val saved: SavedStateHandle, private val app: AppContainer, private val operations: Operations,
    /** Called once a save has committed. */
    private val onSaved: () -> Unit) {
    val open: StateFlow<Boolean> = saved.getStateFlow(OPEN, false)
    /** The source being edited; null when adding. */
    val editingId: StateFlow<String?> = saved.getStateFlow(EDITING, null)
    /** Until when the server asked not to retry the URL that was last tried. */
    val retryAt: StateFlow<Long> = saved.getStateFlow(RETRY_AT, 0L)
    private val fetching = MutableStateFlow(false)
    /** True only while a save is fetching and validating its candidate (the dialog shows progress and a stop action). */
    val saving: StateFlow<Boolean> = fetching.asStateFlow()
    private var job: Job? = null

    fun edit(id: String?) { saved[EDITING] = id; saved[OPEN] = true }
    fun dismiss() { saved[OPEN] = false }

    fun save(draft: SourceDraft) { job = operations.run {
        try {
            fetching.value = true
            val url = SourceRules.url(draft.url).toString()
            if (saved.get<String>(RETRY_URL) == url && retryAt.value > app.now()) throw DictionaryProblem("SERVER_WAIT")
            try { app.editor.save(editingId.value, draft) }
            catch (problem: DictionaryProblem) {
                HttpRetry.retryAfter(problem.retryAfter, app.now())?.let { saved[RETRY_URL] = url; saved[RETRY_AT] = it }
                throw problem
            }
        } finally { fetching.value = false }
        saved[RETRY_AT] = 0L
        // Completion updates the current screen even if the dialog that started it was recreated.
        onSaved(); dismiss()
        // The new generation is written to the shared dictionary by the apply chain, not on this screen.
        app.scheduler.repair(userRequested = true)
    } }

    /**
     * Abandons the fetch of a save; nothing has been stored yet and the draft stays in the dialog. Only while fetching:
     * afterwards the candidate is committed and its shared-dictionary write has begun.
     */
    fun cancel() { if (fetching.value) job?.cancel() }

    private companion object {
        const val OPEN = "formOpen"
        const val EDITING = "editingId"
        const val RETRY_AT = "formRetryAt"
        const val RETRY_URL = "formRetryUrl"
    }
}
