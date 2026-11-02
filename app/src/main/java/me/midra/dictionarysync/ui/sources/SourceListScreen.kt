package me.midra.dictionarysync.ui.sources

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.midra.dictionarysync.R
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.ui.AppViewModel
import me.midra.dictionarysync.sync.SyncReadiness
import me.midra.dictionarysync.ui.common.*

/**
 * Source list (list pane). Selection mode replaces the app bar and shows a floating toolbar for batch actions.
 * Without [showFab] adding is left to the navigation rail; [snackbar] is shared with it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SourceListScreen(model: AppViewModel, openedSourceId: String?, snackbar: SnackbarHostState, onAdd: () -> Unit, showFab: Boolean,
    onOpenSource: (String) -> Unit, onOpenDiagnostics: () -> Unit) {
    val list = model.sources
    val loaded by list.loaded.collectAsStateWithLifecycle()
    val sources by list.sources.collectAsStateWithLifecycle()
    val counts by list.counts.collectAsStateWithLifecycle()
    val requests by list.requests.collectAsStateWithLifecycle()
    val selection by list.selection.collectAsStateWithLifecycle()
    val notice by list.notice.collectAsStateWithLifecycle()
    val busy by model.operations.busy.collectAsStateWithLifecycle()
    val pending by model.review.pending.collectAsStateWithLifecycle()
    val readiness by model.settings.readiness.collectAsStateWithLifecycle()
    var selecting by rememberSaveable { mutableStateOf(false) }
    var deleteIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    val now by produceState(System.currentTimeMillis(), sources) {
        value = System.currentTimeMillis()
        while (true) { delay(60_000); value = System.currentTimeMillis() }
    }
    // Status is computed per row from the row's own source, so list and status can never disagree.
    val summary = remember(sources, requests, now) { summarize(sources.map { s -> sourceStatus(s, requests.find { it.sourceId == s.id }, now) }) }
    val haptics = LocalHapticFeedback.current
    val resultMessage = notice?.let { batchMessage(it, selecting) }

    LaunchedEffect(notice) {
        val result = notice ?: return@LaunchedEffect
        haptics.performHapticFeedback(if (result.failed == 0) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        // A single switch outside selection mode reports through the row status; the failed id is not kept selected.
        if (!selecting && result.succeeded + result.failed == 1) list.clearSelection()
        if (resultMessage != null) snackbar.showSnackbar(resultMessage, withDismissAction = true, duration = SnackbarDuration.Long)
        list.consumeNotice()
    }
    fun exitSelection() { selecting = false; list.clearSelection() }
    androidx.activity.compose.BackHandler(selecting) { exitSelection() }

    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selecting) TopAppBar(
                title = { Text(stringResource(R.string.selection_count, selection.size)) },
                navigationIcon = { IconButton(onClick = ::exitSelection) { Icon(AppIcons.Close, stringResource(R.string.selection_exit)) } },
                actions = {
                    TextButton(onClick = list::selectAll, enabled = !busy) { Text(stringResource(R.string.selection_all)) }
                    TextButton(onClick = list::clearSelection, enabled = !busy && selection.isNotEmpty()) { Text(stringResource(R.string.selection_clear)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) else TopAppBar(
                title = { Text(stringResource(R.string.sources_title)) },
                subtitle = { Text(summaryText(summary)) },
                actions = {
                    TooltipIconButton(AppIcons.Sync, stringResource(R.string.action_sync_all), { list.fetchAll() },
                        enabled = !busy && sources.any { it.syncable })
                    TextButton(onClick = { selecting = true }, enabled = !busy && sources.isNotEmpty()) { Text(stringResource(R.string.action_select)) }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(showFab && !selecting, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                SmallExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.action_add_source)) },
                    // The extended FAB takes its accessibility label from the icon; the text is hidden from semantics.
                    icon = { Icon(AppIcons.Add, contentDescription = stringResource(R.string.action_add_source)) },
                    onClick = onAdd,
                    expanded = fabExpanded,
                )
            }
        },
        // Keep messages above the floating selection toolbar so they never cover its actions.
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (selecting) 88.dp else 0.dp)) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize().testTag("source-list"), state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                pending?.let {
                    item(key = "pending") {
                        Banner(Tone.Problem, stringResource(R.string.banner_interrupted_title), stringResource(R.string.banner_interrupted_body),
                            Modifier.padding(bottom = 12.dp).animateItem(), stringResource(R.string.banner_interrupted_action), model.review::open, enabled = !busy)
                    }
                }
                val readinessTitle = when (readiness) {
                    SyncReadiness.IME_DISABLED -> R.string.banner_ime_title
                    SyncReadiness.DIAGNOSIS_REQUIRED -> R.string.banner_diagnosis_title
                    SyncReadiness.PROBE_UNFINISHED -> R.string.banner_unfinished_title
                    else -> null
                }
                if (readinessTitle != null && sources.any { it.syncable }) {
                    item(key = "readiness") {
                        Banner(Tone.Attention, stringResource(readinessTitle), stringResource(R.string.banner_diagnosis_body),
                            Modifier.padding(bottom = 12.dp).animateItem(), stringResource(R.string.banner_diagnosis_action), onOpenDiagnostics)
                    }
                }
                when {
                    loaded == null -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            val loading = stringResource(R.string.sources_loading)
                            LoadingIndicator(Modifier.semantics { contentDescription = loading })
                        }
                    }
                    sources.isEmpty() -> item(key = "empty") { EmptySources(Modifier.animateItem()) }
                    else -> itemsIndexed(sources, key = { _, s -> s.id }) { index, source ->
                        SourceRow(
                            source = source, status = sourceStatus(source, requests.find { it.sourceId == source.id }, now),
                            count = counts.find { it.sourceId == source.id }?.count,
                            index = index, total = sources.size, selecting = selecting, selected = source.id in selection,
                            opened = !selecting && source.id == openedSourceId, busy = busy, now = now,
                            onClick = { if (selecting) list.toggleSelection(source.id) else onOpenSource(source.id) },
                            onLongClick = {
                                if (!selecting) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selecting = true; list.toggleSelection(source.id)
                                }
                            },
                            onEnabled = { value ->
                                haptics.performHapticFeedback(if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                list.setEnabled(source.id, value)
                            },
                            onSync = { list.fetch(source.id) }, onEdit = { model.form.edit(source.id) }, onDelete = { deleteIds = listOf(source.id) },
                            onRetry = { list.retry(source.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            AnimatedVisibility(selecting, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()) {
                    val enabled = !busy && selection.isNotEmpty()
                    // Text buttons default to primary, which is too faint on the vibrant (primary container) toolbar.
                    val colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
                    TextButton(onClick = { list.batch(selection, BatchOperation.ENABLE) }, enabled = enabled, colors = colors) { Text(stringResource(R.string.action_enable)) }
                    TextButton(onClick = { list.batch(selection, BatchOperation.DISABLE) }, enabled = enabled, colors = colors) { Text(stringResource(R.string.action_disable)) }
                    TextButton(onClick = { deleteIds = selection.toList() }, enabled = enabled, colors = colors) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
    }
    if (deleteIds.isNotEmpty()) DeleteDialog(deleteIds.size, onConfirm = { list.batch(deleteIds, BatchOperation.DELETE); deleteIds = emptyList() },
        onDismiss = { deleteIds = emptyList() })
}

@Composable
private fun summaryText(summary: ListSummary): String = when {
    summary.total == 0 -> stringResource(R.string.sources_subtitle_empty)
    summary.needsAttention > 0 -> stringResource(R.string.sources_subtitle_attention, summary.total, summary.needsAttention)
    summary.working > 0 -> stringResource(R.string.sources_subtitle_working, summary.total)
    else -> stringResource(R.string.sources_subtitle_ok, summary.total)
}

@Composable
private fun batchMessage(result: BatchResult, selecting: Boolean): String? {
    // A single switch reports through its row (and an error dialog if it was refused).
    if (!selecting && result.succeeded + result.failed == 1) return null
    if (result.failed == 0) return stringResource(R.string.batch_result, result.succeeded)
    val text = stringResource(R.string.batch_result_failed, result.succeeded, result.failed)
    return if (selecting) text + "\n" + stringResource(R.string.batch_result_failed_hint) else text
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SourceRow(
    source: SourceEntity, status: SourceStatus, count: Int?, index: Int, total: Int,
    selecting: Boolean, selected: Boolean, opened: Boolean, busy: Boolean, now: Long,
    onClick: () -> Unit, onLongClick: () -> Unit, onEnabled: (Boolean) -> Unit,
    onSync: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier, onRetry: () -> Unit = {},
) {
    val selectionDescription = if (selecting) stringResource(if (selected) R.string.source_selected else R.string.source_unselected, source.name) else null
    SegmentedListItem(
        selected = if (selecting) selected else opened,
        onClick = onClick,
        onLongClick = if (selecting) null else onLongClick,
        onLongClickLabel = stringResource(R.string.source_start_selection),
        shapes = ListItemDefaults.segmentedShapes(index, total),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.semantics { if (selectionDescription != null) contentDescription = selectionDescription },
        leadingContent = if (selecting) ({ Checkbox(checked = selected, onCheckedChange = null) }) else null,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val active = source.listed
                val switchDescription = stringResource(if (source.enabled) R.string.source_switch_on else R.string.source_switch_off, source.name)
                Switch(checked = source.enabled, onCheckedChange = onEnabled, enabled = !busy && !selecting && active,
                    modifier = Modifier.testTag("source-enabled-${source.id}").semantics { contentDescription = switchDescription })
                if (!selecting) SourceMenu(source, status, busy, onSync, onEdit, onDelete, onRetry)
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                Text(metaText(source, count, now), style = MaterialTheme.typography.bodyMedium)
                StatusLine(status)
                if (status.kind == StatusKind.Fetching || status.kind == StatusKind.Reflecting)
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        },
    ) { Text(source.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun metaText(source: SourceEntity, count: Int?, now: Long): String {
    val parts = buildList {
        add(if (count != null) stringResource(R.string.source_words, formatCount(count)) else stringResource(R.string.source_updated_never))
        add(stringResource(R.string.source_interval_every, intervalText(source.syncIntervalHours)))
        if (count != null) add(updatedText(updatedAt(source.fetchedAt, now)))
    }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceMenu(source: SourceEntity, status: SourceStatus, busy: Boolean, onSync: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onRetry: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !busy) { Icon(AppIcons.MoreVert, stringResource(R.string.action_more)) }
        // Expressive menu: routine actions in one group, the destructive one apart in its own.
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            val active = source.listed
            // A failed write can be queued again once its cause (diagnosis, keyboard) is fixed.
            val retry = status.kind == StatusKind.ReflectFailed
            val count = if (retry) 3 else 2
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                if (retry) DropdownMenuItem(text = { Text(stringResource(R.string.menu_retry)) }, shape = MenuDefaults.itemShape(0, count).shape,
                    leadingIcon = { Icon(AppIcons.History, null) }, onClick = { expanded = false; onRetry() })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_sync_now)) }, shape = MenuDefaults.itemShape(count - 2, count).shape,
                    leadingIcon = { Icon(AppIcons.Sync, null) }, onClick = { expanded = false; onSync() }, enabled = source.enabled && active)
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_edit)) }, shape = MenuDefaults.itemShape(count - 1, count).shape,
                    leadingIcon = { Icon(AppIcons.Edit, null) }, onClick = { expanded = false; onEdit() }, enabled = active)
            }
            Spacer(Modifier.height(MenuDefaults.GroupSpacing))
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_delete)) }, shape = MenuDefaults.itemShape(0, 1).shape,
                    leadingIcon = { Icon(AppIcons.Delete, null) }, onClick = { expanded = false; onDelete() })
            }
        }
    }
}

@Composable
private fun DeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.delete_title, count)) },
        text = { Text(stringResource(R.string.delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EmptySources(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center) {
            Icon(AppIcons.Books, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
        }
        Text(stringResource(R.string.sources_empty_title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Text(stringResource(R.string.sources_empty_body), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
