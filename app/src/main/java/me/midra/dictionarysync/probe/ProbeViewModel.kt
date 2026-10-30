package me.midra.dictionarysync.probe

import android.app.Application
import android.database.SQLException
import android.os.RemoteException
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.midra.dictionarysync.R
import me.midra.dictionarysync.sync.*

data class ProbeUiState(
    val marker: String = "",
    val busy: Boolean = true,
    val imeEnabled: Boolean = false,
    val report: String = "",
    val trialReport: String = "",
    val readConfirmed: Boolean = false,
    val trialUnfinished: Boolean = false,
    val status: Int = R.string.probe_not_run,
    /** A current diagnosis certificate exists for this Provider (same check the source list uses). */
    val certified: Boolean = false,
    /** Which step [status] belongs to: 0 none, 2 read, 3 write trial. */
    val statusStep: Int = 0,
)

class ProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ProbeUiState())
    val state = mutableState.asStateFlow()
    private val probeGateway = AndroidProbeGateway(application.contentResolver)
    private lateinit var files: ProbeFiles

    override fun onCleared() {
        probeGateway.close()
    }

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    files = ProbeFiles(application)
                    files.marker() to files.lastReport()
                }
                mutableState.value = ProbeUiState(
                    marker = loaded.first, report = loaded.second,
                    busy = false, imeEnabled = isImeEnabled(),
                    trialReport = withContext(Dispatchers.IO) { AndroidProbeJournal(application).report() },
                    trialUnfinished = withContext(Dispatchers.IO) { AndroidProbeJournal(application).load()?.finished == false },
                    certified = certified(),
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = ProbeUiState(busy = false, status = R.string.probe_storage_error)
            }
        }
    }

    fun refreshImeState() {
        if (state.value.busy) return
        mutableState.value = state.value.copy(imeEnabled = isImeEnabled())
        viewModelScope.launch {
            val current = certified()
            if (!state.value.busy) mutableState.value = state.value.copy(certified = current)
        }
    }

    private suspend fun certified(): Boolean = withContext(Dispatchers.IO) {
        try { AndroidSyncGateway(getApplication()).readiness() == SyncReadiness.READY }
        catch (error: Exception) { if (error is CancellationException) throw error; false }
    }

    private fun isImeEnabled(): Boolean = getApplication<Application>()
        .getSystemService(InputMethodManager::class.java).enabledInputMethodList
        .any { it.packageName == getApplication<Application>().packageName }

    fun read(manuallyConfirmed: Boolean) {
        val initial = state.value
        if (initial.busy || initial.marker.isEmpty()) return
        mutableState.value = initial.copy(busy = true)
        viewModelScope.launch {
            val completed = withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                var status: Int
                var confirmedRead = false
                val details = try {
                    val (summary, verdict) = ReadProbe(AndroidDictionaryReader(context.contentResolver))
                        .run(initial.marker)
                    confirmedRead = verdict == ReadVerdict.READ_CONFIRMED
                    status = when (verdict) {
                        ReadVerdict.READ_CONFIRMED -> R.string.probe_read_confirmed
                        ReadVerdict.EMPTY_UNVERIFIED -> R.string.probe_empty
                        ReadVerdict.MARKER_NOT_VISIBLE -> R.string.probe_not_visible
                        ReadVerdict.AMBIGUOUS_MARKER -> R.string.probe_ambiguous
                    }
                    buildString {
                        appendLine("query verdict: $verdict")
                        appendLine("visible row count: ${summary.rowCount}")
                        appendLine("marker matches (capped at 2): ${summary.markerRows.size}")
                        // Only the exact synthetic marker is recorded. No other dictionary contents.
                        summary.markerRows.forEach { row ->
                            appendLine("marker _id=${row.id}, word=${row.word}")
                            appendLine("shortcut=${row.shortcut}, locale=${row.locale}, frequency=${row.frequency}, appid=${row.appId}")
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    status = when (error) {
                        is SecurityException -> R.string.probe_access_denied
                        is NullCursorException -> R.string.probe_null_cursor
                        is MissingColumnException -> R.string.probe_missing_column
                        is IllegalArgumentException -> R.string.probe_invalid_query
                        is SQLException -> R.string.probe_database_error
                        is RemoteException -> R.string.probe_remote_error
                        else -> R.string.probe_unknown_error
                    }
                    // Provider exception messages may include SQL or private data; never record them.
                    "query exception class: ${error.javaClass.name}\n"
                }
                val report = buildString {
                    appendLine("DictionarySync read diagnostic")
                    appendLine("UTC: ${Instant.now()}")
                    appendLine("marker: ${initial.marker}")
                    appendLine("user confirmed marker in Settings: $manuallyConfirmed")
                    append(details)
                    appendLine(context.getString(status))
                    appendLine("This read action performs no insert/update/delete; see separate trial journal")
                    appendLine("This read report does not evaluate CRUD or IME candidate display")
                    appendLine("Manual marker cleanup: confirm in Settings; not inferred from query")
                }
                try {
                    files.saveReport(report)
                    initial.copy(busy = false, status = status, statusStep = 2, report = report, readConfirmed = confirmedRead)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    initial.copy(busy = false, status = R.string.probe_storage_error, statusStep = 2, report = report)
                }
            }
            mutableState.value = completed
        }
    }

    fun runBasic() = runTrial("basic")
    fun cleanupTrial() = runTrial("cleanup")
    fun inspectTrial() = runTrial("inspect")

    private fun runTrial(action: String) {
        val initial = state.value
        if (initial.busy || initial.marker.isEmpty()) return
        mutableState.value = initial.copy(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val journal = AndroidProbeJournal(getApplication())
                var detail = ""
                var status = R.string.probe_trial_done
                try {
                    SyncCoordinator.locked(getApplication()) {
                        val probe = WriteProbe(probeGateway, journal)
                        when (action) {
                            "basic" -> { probe.basic(initial.marker); AndroidSyncGateway(getApplication()).certify() }
                            "cleanup" -> probe.cleanup()
                            "inspect" -> { detail = probe.inspect() }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    status = R.string.probe_trial_stopped
                    detail = (error as? ProbeStopped)?.reason ?: error.javaClass.simpleName
                }
                try {
                    initial.copy(busy = false, status = status, statusStep = 3,
                        trialReport = detail + "\n" + journal.report(),
                        trialUnfinished = journal.load()?.finished == false, certified = certified())
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    initial.copy(busy = false, status = R.string.probe_storage_error, statusStep = 3, trialReport = detail)
                }
            }
            mutableState.value = result
        }
    }
}
