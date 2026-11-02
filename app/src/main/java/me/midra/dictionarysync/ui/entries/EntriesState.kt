package me.midra.dictionarysync.ui.entries

import androidx.lifecycle.SavedStateHandle
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import me.midra.dictionarysync.data.local.EntryEntity
import me.midra.dictionarysync.data.local.LibraryDao
import me.midra.dictionarysync.data.local.SnapshotEntity
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.source.SourceRules

/** The read-only word list of the selected source's latest generation, searchable by word and reading. Works offline. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class EntriesState(scope: CoroutineScope, private val saved: SavedStateHandle, private val dao: LibraryDao,
    sources: StateFlow<List<SourceEntity>>) {
    private val sharing = SharingStarted.WhileSubscribed(5000)

    val selected: StateFlow<String?> = saved.getStateFlow(SOURCE, null)
    val query: StateFlow<String> = saved.getStateFlow(QUERY, "")
    val snapshots: StateFlow<List<SnapshotEntity>> = selected.flatMapLatest { if (it == null) flowOf(emptyList()) else dao.snapshots(it) }
        .stateIn(scope, sharing, emptyList())
    /** The generation shown: the selected source's latest. */
    val snapshot: StateFlow<String?> = combine(sources, selected) { list, id -> list.find { it.id == id }?.latestSnapshot }
        .stateIn(scope, sharing, null)
    val entries: Flow<PagingData<EntryEntity>> = combine(snapshot, query.debounce(SEARCH_DEBOUNCE_MILLIS)) { snapshot, search -> snapshot to search }
        .flatMapLatest { (snapshot, search) ->
            if (snapshot == null) flowOf(PagingData.empty())
            else Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) { dao.search(snapshot, SourceRules.like(search)) }.flow
        }.cachedIn(scope)
    /** Languages of the shown generation; more than one means each row names its own. */
    val locales: StateFlow<List<String>> = snapshot.flatMapLatest { if (it == null) flowOf(emptyList()) else dao.locales(it) }
        .stateIn(scope, sharing, emptyList())

    fun select(id: String?) { saved[SOURCE] = id; saved[QUERY] = "" }
    fun search(value: String) { saved[QUERY] = value }

    private companion object {
        const val SOURCE = "source"
        const val QUERY = "query"
        const val PAGE_SIZE = 60
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}
