package me.midra.dictionarysync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import me.midra.dictionarysync.AppContainer
import me.midra.dictionarysync.appContainer
import me.midra.dictionarysync.ui.common.Operations
import me.midra.dictionarysync.ui.editor.SourceFormState
import me.midra.dictionarysync.ui.entries.EntriesState
import me.midra.dictionarysync.ui.review.SyncReviewState
import me.midra.dictionarysync.ui.settings.SettingsState
import me.midra.dictionarysync.ui.sources.SourceListState

/**
 * The state of the main screens, one holder per screen. They share [operations], so the user runs one operation at a
 * time across all of them, and one [SavedStateHandle], so selections, the open form and the search survive recreation.
 */
class AppViewModel @JvmOverloads constructor(application: Application, saved: SavedStateHandle,
    app: AppContainer = application.appContainer) : AndroidViewModel(application) {
    val operations: Operations = Operations(viewModelScope) { app.scheduler.repair() }
    val sources: SourceListState = SourceListState(viewModelScope, saved, app, operations, onDeleted = { deleted ->
        if (entries.selected.value in deleted) entries.select(null)
    })
    val entries: EntriesState = EntriesState(viewModelScope, saved, app.database.library(), sources.sources)
    val form: SourceFormState = SourceFormState(saved, app, operations, onSaved = { entries.select(null) })
    val settings: SettingsState = SettingsState(viewModelScope, application, app, operations)
    val review: SyncReviewState = SyncReviewState(viewModelScope, app, operations)

    init {
        // A fetch cut off with the process is marked, and work lost between saving and enqueueing is scheduled again.
        operations.run { app.fetcher.markInterrupted(); app.scheduler.repair() }
    }
}
