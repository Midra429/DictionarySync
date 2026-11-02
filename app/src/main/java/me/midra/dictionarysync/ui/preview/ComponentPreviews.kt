package me.midra.dictionarysync.ui.preview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.midra.dictionarysync.data.local.EntryEntity
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.ui.entries.EntryRow
import me.midra.dictionarysync.sync.SyncKey
import me.midra.dictionarysync.sync.SyncPlan
import me.midra.dictionarysync.sync.SyncPreview
import me.midra.dictionarysync.ui.common.Banner
import me.midra.dictionarysync.R
import me.midra.dictionarysync.probe.ProbeUiState
import me.midra.dictionarysync.sync.SyncReadiness
import me.midra.dictionarysync.ui.diagnostics.DiagnosticsContent
import me.midra.dictionarysync.ui.setup.SetupContent
import me.midra.dictionarysync.data.local.FetchState
import me.midra.dictionarysync.data.local.SyncState
import me.midra.dictionarysync.sync.PlanState
import me.midra.dictionarysync.ui.editor.SourceForm
import me.midra.dictionarysync.ui.review.SyncReview
import me.midra.dictionarysync.ui.sources.*
import me.midra.dictionarysync.ui.theme.DictionarySyncTheme

private const val NOW = 1_800_000_000_000L

private val samples = listOf(
    SourceEntity("a", "IME共通辞書", "https://example.org/a", latestSnapshot = "s", activeSnapshot = "s", state = FetchState.FETCHED, syncState = SyncState.SYNCED, fetchedAt = NOW - 3 * 3_600_000),
    SourceEntity("b", "地名辞書（とても長い名前のソースで折り返しを確認する）", "https://example.org/b", latestSnapshot = "s", state = FetchState.FETCH_FAILED, error = "HTTP_404", syncState = SyncState.SYNCED, fetchedAt = NOW - 30 * 3_600_000),
    SourceEntity("c", "人名辞書", "https://example.org/c", latestSnapshot = "s", state = FetchState.FETCHED, syncState = SyncState.SYNC_FAILED, syncError = "DIAGNOSIS_REQUIRED", syncIntervalHours = 168, fetchedAt = NOW - 60_000),
    SourceEntity("d", "取得中のソース", "https://example.org/d", latestSnapshot = "s", state = FetchState.FETCHING, fetchedAt = NOW - 2 * 3_600_000),
    SourceEntity("e", "無効にしたソース", "https://example.org/e", latestSnapshot = "s", enabled = false, syncState = SyncState.REMOVED, syncIntervalHours = 6, fetchedAt = NOW - 50 * 60_000),
)

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    DictionarySyncTheme(dynamicColor = false) { Surface(color = MaterialTheme.colorScheme.surface) { content() } }
}

@DictionaryPreviews
@Composable
private fun SourceRowsPreview() = PreviewSurface {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        samples.forEachIndexed { index, source ->
            SourceRow(source, sourceStatus(source, null, NOW), 5708, index, samples.size, selecting = false, selected = false,
                opened = index == 0, busy = false, now = NOW, onClick = {}, onLongClick = {}, onEnabled = {}, onSync = {}, onEdit = {}, onDelete = {})
        }
    }
}

@DictionaryPreviews
@Composable
private fun SelectionRowsPreview() = PreviewSurface {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        samples.take(2).forEachIndexed { index, source ->
            SourceRow(source, sourceStatus(source, null, NOW), 120, index, 2, selecting = true, selected = index == 0,
                opened = false, busy = false, now = NOW, onClick = {}, onLongClick = {}, onEnabled = {}, onSync = {}, onEdit = {}, onDelete = {})
        }
    }
}

@DictionaryPreviews
@Composable
private fun BannersPreview() = PreviewSurface {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Banner(Tone.Problem, "反映が中断しています", "続きを反映するか取り消すかを選んでください。", action = "確認する", onAction = {})
        Banner(Tone.Attention, "反映するには対応診断が必要です", "単語の取得と閲覧はこのまま使えます。", action = "診断を開く", onAction = {})
        Banner(Tone.Positive, "診断は完了しています。", null)
    }
}

@DictionaryPreviews
@Composable
private fun EmptySourcesPreview() = PreviewSurface { EmptySources() }

@DictionaryPreviews
@Composable
private fun SourceFormPreview() = PreviewSurface { SourceForm(null, busy = false, onDismiss = {}, error = "HTTP_404") {} }

@DictionaryPreviews
@Composable
private fun SourceFormBusyPreview() = PreviewSurface { SourceForm(samples[0], busy = true, onDismiss = {}) {} }

@DictionaryPreviews
@Composable
private fun SyncReviewPreview() = PreviewSurface {
    val keys = (1..4).map { SyncKey.of("ja", "追加語$it", "よみ$it") }
    val removed = SyncKey.of("ja", "削除語", "さくじょ")
    val plan = SyncPlan("p", "a", 1, "old", "new", "install", "fp", "0123456789abcdef0123", approved = true,
        state = PlanState.RECOVERY_REQUIRED, error = "EXTERNAL_EDIT", createdAt = 1)
    SyncReview(SyncPreview(plan, keys, listOf(removed), listOf(removed), 1, 0, 1240), busy = false, sources = samples,
        close = {}, cancel = {}, apply = {}, recovery = {})
}

@DictionaryPreviews
@Composable
private fun DiagnosticsInProgressPreview() = PreviewSurface {
    DiagnosticsContent(ProbeUiState(marker = "dictionarysync_check_7e4c1c58", busy = false, imeEnabled = true, readConfirmed = true,
        status = R.string.probe_read_confirmed, statusStep = 2), {}, {}, {}, {}, {}, {}, {})
}

@DictionaryPreviews
@Composable
private fun SetupPreview() = PreviewSurface {
    SetupContent(batteryOptimized = true, notificationsEnabled = false, SyncReadiness.IME_DISABLED, {}, {}, {})
}

@DictionaryPreviews
@Composable
private fun DiagnosticsDonePreview() = PreviewSurface {
    DiagnosticsContent(ProbeUiState(marker = "dictionarysync_check_7e4c1c58", busy = false, imeEnabled = true, readConfirmed = true,
        certified = true, trialUnfinished = true, status = R.string.probe_trial_stopped, statusStep = 3), {}, {}, {}, {}, {}, {}, {})
}

@DictionaryPreviews
@Composable
private fun EntryRowsPreview() = PreviewSurface {
    val entries = listOf(
        EntryEntity("s", 1, "とうきょう", "東京", "地名", "コメント付きの行。長いコメントは3行までで省略します。", "ja-JP"),
        EntryEntity("s", 2, "brb", "be right back", "", "", "en-GB"),
        EntryEntity("s", 3, "", "complimarde", "", "", "fr-CA"),
        EntryEntity("s", 4, "にこにこ", "ニコニコ", "名詞", "", "ALL"),
    )
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        entries.forEachIndexed { index, entry -> EntryRow(entry, ListItemDefaults.segmentedShapes(index, entries.size), showLanguage = true) }
    }
}
