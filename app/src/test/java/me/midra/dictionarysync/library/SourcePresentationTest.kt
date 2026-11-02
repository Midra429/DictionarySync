package me.midra.dictionarysync.library

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.data.source.SourceRules
import me.midra.dictionarysync.sync.PendingRequest
import me.midra.dictionarysync.sync.WaitReason
import me.midra.dictionarysync.ui.sources.*
import me.midra.dictionarysync.data.local.FetchState
import me.midra.dictionarysync.data.local.SourceLifecycle
import me.midra.dictionarysync.data.local.SyncState

class SourcePresentationTest {
    private val now = 1_800_000_000_000L
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test fun relativeTimeIncludesExactly24HoursAndFutureUsesLocalDate() {
        assertEquals(UpdatedAt.Never, updatedAt(null, now, zone))
        assertEquals(UpdatedAt.WithinHour, updatedAt(now - 3_599_999, now, zone))
        assertEquals(UpdatedAt.HoursAgo(1), updatedAt(now - 3_600_000, now, zone))
        assertEquals(UpdatedAt.HoursAgo(3), updatedAt(now - 3 * 3_600_000 - 1, now, zone))
        assertEquals(UpdatedAt.HoursAgo(24), updatedAt(now - 86_400_000, now, zone))
        assertTrue(updatedAt(now - 86_400_001, now, zone) is UpdatedAt.On)
        assertEquals(UpdatedAt.On("2027/01/15 17:00"), updatedAt(now + 1, now, zone))
    }

    @Test fun autoLanguageIsDistinctFromAllAndFormsDoNotImposeTextLimits() {
        assertEquals("AUTO", SourceRules.locale("AUTO"))
        assertEquals("ja", SourceRules.resolvedLocale("AUTO"))
        assertEquals("ALL", SourceRules.resolvedLocale("ALL"))
        SourceRules.name("長い名前".repeat(100))
        assertEquals("https://example.org/a?x=1&x=2&q=%2f+%20", SourceRules.url(" https://example.org/a?x=1&x=2&q=%2f+%20#fragment ").toString())
    }

    private fun source(vararg changes: (SourceEntity) -> SourceEntity) =
        changes.fold(SourceEntity("A", "名前", "https://example.org/a", latestSnapshot = "s", activeSnapshot = "s", state = FetchState.FETCHED, syncState = SyncState.SYNCED)) { s, f -> f(s) }
    private fun request(notBefore: Long = 0, waitReason: WaitReason? = null) = PendingRequest("A", 2, 1, 1, now, notBefore, 1, waitReason = waitReason)

    @Test fun oneStatusPerSourceInPriorityOrder() {
        assertEquals(StatusKind.Synced, sourceStatus(source(), null, now).kind)
        assertEquals(StatusKind.Fetching, sourceStatus(source({ it.copy(state = FetchState.FETCHING, syncError = "X") }), null, now).kind)
        assertEquals(StatusKind.DeletePending, sourceStatus(source({ it.copy(lifecycle = SourceLifecycle.DELETE_PENDING, state = FetchState.FETCHING, syncError = "EXTERNAL_EDIT") }), null, now).kind)
        assertEquals(StatusKind.Interrupted, sourceStatus(source({ it.copy(syncState = SyncState.RECOVERY_REQUIRED, syncError = "INSERT_UNKNOWN") }), null, now).kind)
        assertEquals(StatusKind.Reflecting, sourceStatus(source({ it.copy(syncState = SyncState.ADDING) }), null, now).kind)
        sourceStatus(source({ it.copy(syncState = SyncState.SYNC_FAILED, syncError = "DIAGNOSIS_REQUIRED") }), null, now).let {
            assertEquals(StatusKind.ReflectFailed, it.kind); assertEquals("DIAGNOSIS_REQUIRED", it.reason); assertEquals(Tone.Problem, it.tone)
        }
        sourceStatus(source({ it.copy(state = FetchState.FETCH_FAILED, error = "HTTP_404") }), null, now).let {
            assertEquals(StatusKind.FetchFailed, it.kind); assertEquals("HTTP_404", it.reason); assertEquals(Tone.Attention, it.tone)
        }
        assertEquals(StatusKind.Queued, sourceStatus(source(), request(), now).kind)
        sourceStatus(source({ it.copy(state = FetchState.FETCH_RETRY, error = "HTTP_503") }), request(now + 60_000, WaitReason.HTTP_RETRY), now).let {
            assertEquals(StatusKind.RetryWait, it.kind); assertEquals(now + 60_000, it.until)
        }
        assertEquals(StatusKind.NetworkWait, sourceStatus(source({ it.copy(state = FetchState.NETWORK_WAIT, error = "WIFI_WAIT") }), request(), now).kind)
        assertEquals(StatusKind.ReflectQueued, sourceStatus(source({ it.copy(syncState = SyncState.UPDATE_PENDING) }), null, now).kind)
        assertEquals(StatusKind.ReflectPending, sourceStatus(source({ it.copy(syncState = SyncState.NOT_SYNCED) }), null, now).kind)
        assertEquals(StatusKind.Deleting, sourceStatus(source({ it.copy(lifecycle = SourceLifecycle.DELETE_PENDING, syncState = SyncState.REMOVE_PENDING) }), null, now).kind)
        assertEquals(StatusKind.Disabled, sourceStatus(source({ it.copy(enabled = false, activeSnapshot = null, syncState = SyncState.REMOVED) }), null, now).kind)
        assertEquals(StatusKind.ReleasePending, sourceStatus(source({ it.copy(enabled = false, syncState = SyncState.REMOVE_PENDING) }), null, now).kind)
        assertEquals(StatusKind.NotFetched, sourceStatus(source({ it.copy(latestSnapshot = null, activeSnapshot = null, state = FetchState.NOT_FETCHED, syncState = SyncState.NOT_SYNCED) }), null, now).kind)
    }

    @Test fun reasonsAreGroupedForShortMessages() {
        assertEquals(Reason.Diagnosis, reasonOf("DIAGNOSIS_REQUIRED"))
        assertEquals(Reason.ImeDisabled, reasonOf("IME_DISABLED"))
        assertEquals(Reason.External, reasonOf("EXTERNAL_EDIT"))
        assertEquals(Reason.Unknown, reasonOf("INSERT_UNKNOWN"))
        assertEquals(Reason.Columns, reasonOf("TSV_COLUMNS_2 (行 3)"))
        assertEquals(Reason.Unsupported, reasonOf("ATOK_AUTO_REPLACE_UNSUPPORTED (行 2)"))
        assertEquals(Reason.Wifi, reasonOf("WIFI_WAIT"))
        assertEquals(Reason.Http, reasonOf("HTTP_404"))
        assertEquals(Reason.Generic, reasonOf("SQLiteFullException"))
    }
}
