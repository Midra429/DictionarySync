package me.midra.dictionarysync.ui.sources

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.midra.dictionarysync.data.local.FetchState
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.local.SourceLifecycle
import me.midra.dictionarysync.data.local.SyncState
import me.midra.dictionarysync.sync.PendingRequest
import me.midra.dictionarysync.sync.WaitReason

/** When the last successful fetch (including a validated 304) happened. Not the shared-dictionary write time. */
sealed interface UpdatedAt {
    data object Never : UpdatedAt
    data object WithinHour : UpdatedAt
    data class HoursAgo(val hours: Int) : UpdatedAt
    data class On(val date: String) : UpdatedAt
}

/** 0–24 hours inclusive is relative; anything older, or in the future after a clock change, is a local date. */
fun updatedAt(time: Long?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): UpdatedAt {
    if (time == null) return UpdatedAt.Never
    val elapsed = now - time
    return when {
        elapsed in 0 until 3_600_000L -> UpdatedAt.WithinHour
        elapsed in 0..86_400_000L -> UpdatedAt.HoursAgo((elapsed / 3_600_000).toInt())
        else -> UpdatedAt.On(dateTime(time, zone))
    }
}

fun dateTime(time: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(time))

enum class Tone { Neutral, Working, Positive, Attention, Problem }

enum class StatusKind {
    DeletePending, Fetching, Interrupted, Reflecting, ReflectFailed, FetchFailed, FetchInterrupted,
    NetworkWait, ServerWait, RetryWait, Queued, Deleting, ReleasePending, Disabled, NotFetched, Reverted, ReflectQueued, ReflectPending, Synced,
}

/** The single most important state of a source. [reason] is an internal code for a short explanation. */
data class SourceStatus(val kind: StatusKind, val tone: Tone, val reason: String? = null, val until: Long? = null)

fun sourceStatus(source: SourceEntity, request: PendingRequest?, now: Long): SourceStatus {
    val waiting = request?.pending == true
    val networkWait = waiting && (source.state == FetchState.NETWORK_WAIT || request.waitReason?.network == true)
    val serverUntil = maxOf(source.serverNotBefore, if (request?.waitReason == WaitReason.SERVER_WAIT) request.notBefore else 0)
    return when {
        source.lifecycle == SourceLifecycle.DELETE_PENDING -> if (source.syncError != null) SourceStatus(StatusKind.DeletePending, Tone.Problem, source.syncError)
            else SourceStatus(StatusKind.Deleting, Tone.Working)
        source.state == FetchState.FETCHING -> SourceStatus(StatusKind.Fetching, Tone.Working)
        source.syncState == SyncState.RECOVERY_REQUIRED || source.syncState == SyncState.RECOVERING ->
            SourceStatus(StatusKind.Interrupted, Tone.Problem, source.syncError)
        source.syncState in setOf(SyncState.PREPARED, SyncState.ADDING, SyncState.REMOVING) -> SourceStatus(StatusKind.Reflecting, Tone.Working)
        source.syncError != null -> SourceStatus(StatusKind.ReflectFailed, Tone.Problem, source.syncError)
        source.state == FetchState.FETCH_FAILED -> SourceStatus(StatusKind.FetchFailed, Tone.Attention, source.error)
        networkWait -> SourceStatus(StatusKind.NetworkWait, Tone.Working, source.error ?: request?.waitReason?.name)
        waiting && serverUntil > now -> SourceStatus(StatusKind.ServerWait, Tone.Working, WaitReason.SERVER_WAIT.name, serverUntil)
        waiting && request.notBefore > now -> SourceStatus(StatusKind.RetryWait, Tone.Working, source.error, request.notBefore)
        waiting -> SourceStatus(StatusKind.Queued, Tone.Working)
        source.state == FetchState.INTERRUPTED -> SourceStatus(StatusKind.FetchInterrupted, Tone.Attention)
        !source.enabled -> if (source.activeSnapshot != null || source.syncState == SyncState.REMOVE_PENDING) SourceStatus(StatusKind.ReleasePending, Tone.Working)
            else SourceStatus(StatusKind.Disabled, Tone.Neutral)
        source.latestSnapshot == null || source.state == FetchState.SETTINGS_CHANGED -> SourceStatus(StatusKind.NotFetched, Tone.Neutral)
        source.syncState == SyncState.FAILED_PRESERVED -> SourceStatus(StatusKind.Reverted, Tone.Attention)
        source.syncState == SyncState.SYNCED -> SourceStatus(StatusKind.Synced, Tone.Positive)
        source.syncState == SyncState.UPDATE_PENDING -> SourceStatus(StatusKind.ReflectQueued, Tone.Working)
        else -> SourceStatus(StatusKind.ReflectPending, Tone.Neutral)
    }
}

/** Short, user-facing groups of internal error codes. The raw code stays available as detail. */
enum class Reason {
    Diagnosis, ImeDisabled, PlanFirst, Refetch, External, Unknown, DeleteFailed, Stale, Incomplete,
    Wifi, Network, ServerWait, CsvManual, Columns, Unsupported, Format, Encoding, Size, Http, Url, Duplicate, Name, Locale, Empty, SourceLimit, Generic,
}

fun reasonOf(code: String): Reason = when {
    code in setOf("DIAGNOSIS_REQUIRED", "READ_WITNESS_LOST", "PROBE_UNFINISHED", "PROVIDER_CHANGED", "PROVIDER_MISSING") -> Reason.Diagnosis
    code == "IME_DISABLED" -> Reason.ImeDisabled
    code == "RECOVER_PLAN_FIRST" -> Reason.PlanFirst
    code in setOf("REFETCH_SETTINGS_CHANGED", "FETCH_REQUIRED") -> Reason.Refetch
    code in setOf("EXTERNAL_EDIT", "EXTERNAL_DELETE", "EXTERNAL_EDIT_OR_DELETE", "BORROWED_LOST", "INSERT_READBACK_MISMATCH") -> Reason.External
    code in setOf("INSERT_UNKNOWN", "OWNERSHIP_UNKNOWN", "INSTALLATION_MISMATCH", "INSTALLATION_LOST", "BINDING_MISSING") -> Reason.Unknown
    code in setOf("DELETE_ZERO", "DELETE_MULTIPLE", "DELETE_READBACK_MISMATCH") -> Reason.DeleteFailed
    code in setOf("RECOVERY_STALE", "PLAN_STALE") -> Reason.Stale
    code in setOf("COMPENSATION_INCOMPLETE", "RESYNC_BLOCKED") -> Reason.Incomplete
    code.startsWith("WIFI_WAIT") || code.startsWith("POLICY_CHANGED") -> Reason.Wifi
    code.startsWith("NETWORK_WAIT") -> Reason.Network
    code.startsWith("SERVER_WAIT") -> Reason.ServerWait
    code.startsWith("CSV_MANUAL_REQUIRED") -> Reason.CsvManual
    code.startsWith("TSV_COLUMNS") || code.startsWith("CSV_") -> Reason.Columns
    code.substringBefore(' ') in setOf("SKK_UNSUPPORTED", "RIME_UNSUPPORTED", "YAML_UNSUPPORTED", "ATOK_DICUT_UNSUPPORTED",
        "ATOK_AUTO_REPLACE_UNSUPPORTED", "GBOARD_VERSION_UNSUPPORTED", "GBOARD_FORMAT_UNSUPPORTED") -> Reason.Unsupported
    code.startsWith("SIZE_LIMIT") -> Reason.Size
    code.startsWith("HTTP_") && code.removePrefix("HTTP_").takeWhile(Char::isDigit).isNotEmpty() -> Reason.Http
    code.startsWith("XML_") || code.substringBefore(' ') in setOf("NOT_DICTIONARY", "NOT_TSV", "UNSUPPORTED_BINARY", "FORMAT_CONFLICT",
        "MIME_FORMAT_CONFLICT", "MS_HEADER_REQUIRED", "MS_FORMAT_REQUIRED", "ENTRY_LIMIT", "FIELD_LIMIT", "RECORD_LIMIT",
        "CONTROL_CHARACTER", "INVALID_UNICODE", "EMPTY_WORD", "UNSUPPORTED_FORMAT", "GBOARD_FORMAT_REQUIRED", "GBOARD_LANGUAGE_TAG") -> Reason.Format
    code.contains("ENCODING") || code.startsWith("DECODE_FAILED") || code.startsWith("UTF32") -> Reason.Encoding
    code == "DUPLICATE_URL" -> Reason.Duplicate
    code.contains("URL") -> Reason.Url
    code == "INVALID_NAME" -> Reason.Name
    code == "INVALID_LOCALE" -> Reason.Locale
    code.startsWith("EMPTY_BODY") || code.startsWith("AMBIGUOUS_EMPTY") -> Reason.Empty
    code == "SOURCE_LIMIT" -> Reason.SourceLimit
    else -> Reason.Generic
}

/** App-bar summary: how many sources need the user's attention, and whether anything is running. */
data class ListSummary(val total: Int, val needsAttention: Int, val working: Int)

fun summarize(statuses: List<SourceStatus>) = ListSummary(statuses.size,
    statuses.count { it.tone == Tone.Problem || it.tone == Tone.Attention }, statuses.count { it.tone == Tone.Working })
