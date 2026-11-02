package me.midra.dictionarysync.ui.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.midra.dictionarysync.R
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.sync.SyncKey
import me.midra.dictionarysync.sync.PlanPurpose
import me.midra.dictionarysync.sync.PlanState
import me.midra.dictionarysync.sync.SyncPreview
import me.midra.dictionarysync.ui.common.*
import me.midra.dictionarysync.ui.sources.Tone

@Composable
fun SyncReviewDialog(preview: SyncPreview, busy: Boolean, sources: List<SourceEntity>, close: () -> Unit,
    cancel: () -> Unit, apply: () -> Unit, recovery: (PlanPurpose) -> Unit) {
    // Saved outside the dialog window so the search survives recreation.
    val query = rememberSaveable(preview.plan.id) { mutableStateOf("") }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SyncReview(preview, busy, sources, close, cancel, apply, recovery, query)
    }
}

/** Review of an unfinished shared-dictionary plan or a recovery plan: what happens, why, and one primary action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SyncReview(preview: SyncPreview, busy: Boolean, sources: List<SourceEntity>, close: () -> Unit,
    cancel: () -> Unit, apply: () -> Unit, recovery: (PlanPurpose) -> Unit,
    queryState: MutableState<String> = rememberSaveable(preview.plan.id) { mutableStateOf("") }) {
    val plan = preview.plan
    val purpose = plan.purpose
    val unknownSource = stringResource(R.string.review_unknown_source)
    fun name(id: String) = sources.find { it.id == id }?.name ?: unknownSource
    var query by queryState
    val additions = remember(preview, query) { preview.additions.filter { query in it.word || query in it.shortcut.orEmpty() } }
    val removed = remember(preview, query) { preview.removed.filter { query in it.word || query in it.shortcut.orEmpty() } }
    val deletes = remember(preview) { preview.ownedDeletes.map { it.id }.toSet() }
    val blocked = purpose == PlanPurpose.RESYNC && preview.blockers.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(when (purpose) {
                    PlanPurpose.RESYNC -> R.string.review_title_resync
                    PlanPurpose.COMPENSATE -> R.string.review_title_compensate
                    PlanPurpose.REMOVE_SOURCE -> R.string.review_title_remove
                    PlanPurpose.APPLY -> R.string.review_title_apply
                })) },
                navigationIcon = { IconButton(onClick = close) { Icon(AppIcons.Close, stringResource(R.string.action_close)) } },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (!plan.approved) TextButton(onClick = cancel, enabled = !busy) { Text(stringResource(R.string.review_cancel_plan)) }
                    Button(onClick = apply, enabled = !busy && !blocked) {
                        if (busy) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
                        else Text(stringResource(when (purpose) {
                            PlanPurpose.RESYNC -> R.string.review_apply_resync
                            PlanPurpose.COMPENSATE -> R.string.review_apply_compensate
                            PlanPurpose.REMOVE_SOURCE -> R.string.review_apply_remove
                            PlanPurpose.APPLY -> R.string.review_apply_apply
                        }))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("sync-all-differences"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "source") { Text(stringResource(R.string.review_source, name(plan.sourceId)), style = MaterialTheme.typography.titleMediumEmphasized) }
            item(key = "stats") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(R.string.review_stat_desired, preview.desiredCount)
                    Stat(R.string.review_stat_add, preview.additions.size)
                    Stat(R.string.review_stat_release, preview.removed.size)
                    Stat(R.string.review_stat_delete, preview.ownedDeletes.size)
                    if (purpose == PlanPurpose.APPLY) Stat(R.string.review_stat_shared, preview.shared)
                    Stat(R.string.review_stat_protected, preview.protected)
                }
            }
            plan.error?.let { code ->
                item(key = "error") { Banner(Tone.Problem, reasonText(code), stringResource(R.string.error_code, code)) }
            }
            if (purpose.recovery) {
                item(key = "detail") {
                    Text(stringResource(when (purpose) {
                        PlanPurpose.RESYNC -> R.string.review_resync_detail
                        PlanPurpose.REMOVE_SOURCE -> R.string.review_remove_detail
                        else -> R.string.review_compensate_detail
                    }), style = MaterialTheme.typography.bodyLarge)
                }
                item(key = "related") { Text(stringResource(R.string.review_related, preview.relatedSources.joinToString("、", transform = ::name))) }
                if (preview.blockers.isNotEmpty()) item(key = "blockers") {
                    Banner(Tone.Problem, stringResource(R.string.review_blockers, preview.blockers.joinToString("、")),
                        stringResource(R.string.review_blockers_detail))
                }
            }
            if (purpose == PlanPurpose.APPLY && plan.approved && plan.state == PlanState.RECOVERY_REQUIRED) {
                item(key = "choose") { Text(stringResource(R.string.review_choose), style = MaterialTheme.typography.bodyLarge) }
                item(key = "resync") {
                    RecoveryOption(stringResource(R.string.review_open_resync), stringResource(R.string.review_resync_detail), !busy) { recovery(PlanPurpose.RESYNC) }
                }
                item(key = "compensate") {
                    RecoveryOption(stringResource(R.string.review_open_compensate), stringResource(R.string.review_compensate_detail), !busy) { recovery(PlanPurpose.COMPENSATE) }
                }
            }
            item(key = "notes") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.review_safety), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.review_ime_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (preview.additions.isNotEmpty() || preview.removed.isNotEmpty()) {
                item(key = "diff-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.review_differences), style = MaterialTheme.typography.titleMediumEmphasized,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                        Text(stringResource(R.string.review_differences_count, additions.size, removed.size), style = MaterialTheme.typography.bodyMedium)
                        SearchField(query, { query = it }, stringResource(R.string.entries_search), Modifier.testTag("differences-search"))
                    }
                }
                items(additions, key = { "add:${it.id}" }) { DifferenceRow(stringResource(R.string.review_diff_add), it, Tone.Positive) }
                items(removed, key = { "remove:${it.id}" }) {
                    if (it.id in deletes) DifferenceRow(stringResource(R.string.review_diff_delete), it, Tone.Problem)
                    else DifferenceRow(stringResource(R.string.review_diff_release), it, Tone.Neutral)
                }
            }
            item(key = "identity") {
                Text(stringResource(R.string.review_identity, plan.token.take(16)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun Stat(label: Int, value: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.widthIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(formatCount(value), style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecoveryOption(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AppIcons.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun DifferenceRow(kind: String, key: SyncKey, tone: Tone) {
    val colors = toneColors(tone)
    ListItem(
        headlineContent = { Text(stringResource(R.string.review_diff_entry, key.shortcut ?: stringResource(R.string.review_no_reading), key.word, key.locale)) },
        overlineContent = { Text(kind, color = colors.accent) },
        leadingContent = { Icon(toneIcon(tone), contentDescription = null, tint = colors.accent) },
        modifier = Modifier.semantics(mergeDescendants = true) {},
    )
}
