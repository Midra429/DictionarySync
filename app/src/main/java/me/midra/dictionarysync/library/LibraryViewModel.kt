package me.midra.dictionarysync.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.data.local.EntryEntity
import me.midra.dictionarysync.data.local.SnapshotEntity
import me.midra.dictionarysync.data.parser.DictionaryProblem
import me.midra.dictionarysync.data.source.HttpRetry
import me.midra.dictionarysync.data.source.SourceRules
import me.midra.dictionarysync.sync.*

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel @JvmOverloads constructor(application: Application, private val saved: SavedStateHandle,
    private val repository: LibraryRepository = LibraryRepository(application),
    private val sync: SyncRepository = SyncRepository(application, repository.database),
    dispatch: FetchDispatch = FetchWork(application),
    private val readinessCheck: suspend () -> SyncReadiness = { AndroidSyncGateway(application).readiness() }) : AndroidViewModel(application) {
    /** null until the first database emission, so the UI can tell "loading" from "no sources". */
    val sourceList = repository.dao.observeSources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val sources = sourceList.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val fetchRequests = repository.database.requests().observePending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sourceCounts = repository.dao.observeCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selection = saved.getStateFlow("selection", arrayListOf<String>())
    /** Result of the last batch operation, shown once by the UI. */
    val notice = MutableStateFlow<BatchResult?>(null)
    /** Whether shared-dictionary writes can currently run; null until first checked. Read-only. */
    val readiness = MutableStateFlow<SyncReadiness?>(null)
    val formRetryAt = saved.getStateFlow("formRetryAt", 0L)
    val formOpen = saved.getStateFlow("formOpen", false)
    val editingId = saved.getStateFlow<String?>("editingId", null)
    val selected = saved.getStateFlow<String?>("source", null)
    val query = saved.getStateFlow("query", "")
    val snapshots = selected.flatMapLatest { if (it == null) flowOf(emptyList()) else repository.dao.snapshots(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<SnapshotEntity>())
    val selectedSnapshot = combine(sources, selected) { list, id -> list.find { it.id == id }?.latestSnapshot }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val entries = combine(selectedSnapshot, query.debounce(250)) { snapshot, search -> snapshot to search }
        .flatMapLatest { (snapshot, search) ->
            if (snapshot == null) flowOf(PagingData.empty<EntryEntity>())
            else Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) { repository.dao.search(snapshot, SourceRules.like(search)) }.flow
        }.cachedIn(viewModelScope)
    /** Languages of the shown generation; more than one means each row names its own. */
    val locales = selectedSnapshot.flatMapLatest { if (it == null) flowOf(emptyList()) else repository.dao.locales(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<String>())
    val wifiOnly = repository.preferences.wifiOnly.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    /** null until read, so the first frame can choose between the setup and the source list. */
    val setupDone = repository.preferences.setupDone.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Whether battery optimization still applies to background work; null until first checked. Read-only. */
    val batteryOptimized = MutableStateFlow<Boolean?>(null)
    /** Whether sync notifications can be shown; null until first checked. */
    val notificationsEnabled = MutableStateFlow<Boolean?>(null)
    private val fetchWork = FetchRequests(repository, dispatch)
    val preview = MutableStateFlow<SyncPreview?>(null)
    // A failed evaluation keeps the last known value and reports once; later plan changes are still observed.
    val pending = repository.database.sync().observePendingPlans().distinctUntilChanged()
        .transformLatest { _ ->
            try { emit(sync.pending()) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error.value = errorCode(failure)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    init { action { repository.reconcileInterruptedFetch(); fetchWork.repair() } }
    fun select(id: String?) { saved["source"] = id; saved["query"] = "" }
    fun search(value: String) { saved["query"] = value }
    fun dismissError() { error.value = null }
    fun consumeNotice() { notice.value = null }
    fun refreshReadiness() {
        viewModelScope.launch {
            readiness.value = try { withContext(Dispatchers.IO) { readinessCheck() } }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                SyncReadiness.UNSUPPORTED
            }
        }
    }
    fun refreshBatteryOptimization() { batteryOptimized.value = BackgroundAccess.batteryOptimized(getApplication()) }
    fun refreshNotifications() { notificationsEnabled.value = BackgroundAccess.notificationsEnabled(getApplication()) }
    fun finishSetup() {
        viewModelScope.launch {
            try { repository.preferences.setSetupDone(true) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error.value = errorCode(failure)
            }
        }
    }
    fun wifiOnly(value: Boolean) = action { repository.setWifiOnly(value); fetchWork.repair() }
    fun toggleSelection(id: String) { saved["selection"] = ArrayList(selection.value.toMutableList().apply { if (!remove(id)) add(id) }) }
    fun selectAll() { saved["selection"] = ArrayList(sources.value.map { it.id }) }
    fun clearSelection() { saved["selection"] = arrayListOf<String>() }
    fun edit(id: String?) { saved["editingId"] = id; saved["formOpen"] = true }
    /** Form errors are shown inline in the form, so they must not outlive it and resurface as a dialog. */
    fun dismissForm() { saved["formOpen"] = false; error.value = null }
    private var saveJob: Job? = null
    /** True only while an add/edit is fetching and validating its candidate (the form shows progress and a stop action). */
    val saving = MutableStateFlow(false)
    fun save(id: String?, name: String, url: String, locale: String, format: String, encoding: String, interval: Int) { saveJob = action {
        try {
            // A new attempt supersedes the previous attempt's error.
            error.value = null
            saving.value = true
            val requestUrl = SourceRules.url(url).toString()
            if (saved.get<String>("formRetryUrl") == requestUrl && formRetryAt.value > System.currentTimeMillis()) throw DictionaryProblem("SERVER_WAIT")
            try { repository.save(id, name, url, locale, format, encoding, interval) }
            catch (problem: DictionaryProblem) {
                HttpRetry.retryAfter(problem.retryAfter, System.currentTimeMillis())?.let {
                    saved["formRetryUrl"] = requestUrl; saved["formRetryAt"] = it
                }
                throw problem
            }
        } finally { saving.value = false }
        saved["formRetryAt"] = 0L
        // Completion must update the current screen even if the initiating composition was recreated.
        select(null); dismissForm()
        // The new generation is written to the shared dictionary by the apply Worker, not on this screen.
        fetchWork.repair(userRequested = true)
    } }
    /** Abandons the in-flight fetch of an add/edit. Nothing has been stored yet; the draft stays in the form.
     * Only while fetching: afterwards the candidate is committed and the shared-dictionary write has begun. */
    fun cancelSave() { if (saving.value) saveJob?.cancel() }
    fun fetch(id: String) = action { fetchWork.one(id) }
    fun fetchAll() = action { fetchWork.all() }
    fun enabled(id: String, value: Boolean) = batch(listOf(id), if (value) "ENABLE" else "DISABLE")
    fun batch(ids: List<String>, operation: String) = action {
        val failed = arrayListOf<String>()
        for (id in ids.distinct()) {
            try {
                // Only the desired state is stored here; Provider writes run in the apply Worker.
                val success = when (operation) {
                    "DELETE" -> sync.delete(id, background = true)
                    else -> sync.setEnabled(id, operation == "ENABLE", background = true)
                }
                if (!success) failed.add(id)
                if (operation == "ENABLE" && repository.dao.source(id)?.enabled == true) repository.requestPeriodicFetches(System.currentTimeMillis(), id)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                failed.add(id)
                error.value = errorCode(failure)
            }
        }
        saved["selection"] = failed
        notice.value = BatchResult(operation, ids.distinct().size - failed.size, failed.size)
        if (operation == "DELETE" && selected.value in ids && selected.value !in failed) select(null)
        fetchWork.repair(userRequested = true)
    }
    /** Re-queues a source whose shared-dictionary write failed (for example after the diagnosis was completed). */
    fun retry(id: String) = action { sync.retry(id); fetchWork.repair(userRequested = true) }
    fun resumeSync() { preview.value = pending.value }
    fun dismissPreview() { preview.value = null }
    fun prepareRecovery(purpose: String) = action {
        val chosen = preview.value ?: return@action
        preview.value = sync.prepareRecovery(chosen.plan.id, purpose)
    }
    fun cancelPlan() = action {
        preview.value?.let { sync.cancel(it.plan.id) }; preview.value = null
    }
    fun applySync() = action {
        val chosen = preview.value ?: return@action
        preview.value = null
        try { sync.apply(chosen.plan.id, chosen.plan.token) }
        finally { fetchWork.repair() }
    }
    private fun action(block: suspend () -> Unit): Job? {
        if (busy.value) return null
        busy.value = true
        return viewModelScope.launch {
            try { block() }
            catch (exception: Exception) {
                if (exception is CancellationException) {
                    // The screen went away mid-operation. Desired states and journaled plans are durable; hand any
                    // remaining shared-dictionary work to the background chain instead of waiting for the next launch.
                    withContext(NonCancellable) {
                        // Best effort: if scheduling fails here, the startup and Worker repair passes enqueue it later.
                        try { fetchWork.repair() } catch (failure: Exception) { if (failure is CancellationException) throw failure }
                    }
                    throw exception
                }
                error.value = errorCode(exception)
            } finally { busy.value = false }
        }
    }
    private fun errorCode(failure: Exception) = (failure as? SyncStopped)?.code ?: (failure as? DictionaryProblem)?.code ?: failure.javaClass.simpleName
}

data class BatchResult(val operation: String, val succeeded: Int, val failed: Int)
