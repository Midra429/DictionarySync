package me.midra.dictionarysync.ui.entries

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import me.midra.dictionarysync.R
import me.midra.dictionarysync.data.local.EntryEntity
import me.midra.dictionarysync.ui.AppViewModel
import me.midra.dictionarysync.ui.common.*

/**
 * Read-only word list of the latest fetched generation (detail pane). The search stays under the app bar and shares its
 * color, which turns to the scrolled tone once the list moves beneath both. Entries are one segmented group, like the
 * source list: the reading sits above the word like furigana, with the part of speech and language as labels.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun EntriesScreen(model: AppViewModel, sourceId: String, onBack: () -> Unit) {
    val state = model.entries
    val loaded by model.sources.loaded.collectAsStateWithLifecycle()
    val selected by state.selected.collectAsStateWithLifecycle()
    val busy by model.operations.busy.collectAsStateWithLifecycle()
    val source = loaded?.find { it.id == sourceId }
    // The back stack is the source of truth; the ViewModel only drives the paged query.
    LaunchedEffect(sourceId, selected, loaded) {
        if (loaded != null && source == null) onBack()
        else if (selected != sourceId) state.select(sourceId)
    }
    val entries = state.entries.collectAsLazyPagingItems()
    val query by state.query.collectAsStateWithLifecycle()
    val snapshots by state.snapshots.collectAsStateWithLifecycle()
    val chosen by state.snapshot.collectAsStateWithLifecycle()
    val snapshot = snapshots.find { it.id == chosen }
    val locales by state.locales.collectAsStateWithLifecycle()
    val mixed = locales.size > 1
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // The scene scope exists only while the source list is beside this pane; then the list itself is the way back.
    val besideList = LocalListDetailSceneScope.current != null
    val colors = MaterialTheme.colorScheme
    // One tone above the rows (surfaceContainer), so the words passing beneath stay distinct from the header.
    val headerColor by animateColorAsState(if (scrollBehavior.state.overlappedFraction > 0.01f) colors.surfaceContainerHigh else colors.surface,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "header")
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Surface(color = headerColor) {
                Column {
                    TopAppBar(
                        title = { Text(source?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        subtitle = {
                            Text(listOfNotNull(snapshot?.let { stringResource(R.string.source_words, formatCount(it.count)) },
                                when { mixed -> stringResource(R.string.entries_languages, locales.size); locales.isEmpty() -> null; else -> languageName(locales.single()) },
                                stringResource(R.string.entries_read_only)).joinToString(" · "), maxLines = 1)
                        },
                        navigationIcon = { if (!besideList) TooltipIconButton(AppIcons.ArrowBack, stringResource(R.string.action_back), onBack) },
                        actions = {
                            TooltipIconButton(AppIcons.Edit, stringResource(R.string.entries_edit_source), { model.form.edit(sourceId) },
                                enabled = !busy && source?.listed == true)
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                        scrollBehavior = scrollBehavior,
                    )
                    SearchField(query, state::search, stringResource(R.string.entries_search),
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp).testTag("entries-search"),
                        containerColor = colors.surfaceContainerHighest)
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("dictionary-entries"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            snapshot?.takeIf { it.skippedLines > 0 }?.let {
                item(key = "skipped") {
                    Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp).testTag("entries-skipped"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AppIcons.Info, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.entries_skipped_lines, it.skippedLines, it.firstSkippedLine ?: 0),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            items(entries.itemCount, key = entries.itemKey { "${it.snapshotId}:${it.line}" }) { index ->
                entries[index]?.let { EntryRow(it, ListItemDefaults.segmentedShapes(index, entries.itemCount), showLanguage = mixed) }
            }
            item(key = "state") {
                when (val refresh = entries.loadState.refresh) {
                    is LoadState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        val loading = stringResource(R.string.entries_loading)
                        LoadingIndicator(Modifier.semantics { contentDescription = loading })
                    }
                    is LoadState.Error -> ShapeMessage(AppIcons.Error, stringResource(R.string.entries_load_error) + "\n" +
                        stringResource(R.string.error_code, refresh.error.javaClass.simpleName), Modifier.padding(vertical = 32.dp))
                    else -> if (entries.itemCount == 0 && snapshot != null) {
                        if (query.isEmpty()) ShapeMessage(AppIcons.Books, stringResource(R.string.entries_empty), Modifier.padding(vertical = 32.dp))
                        else ShapeMessage(AppIcons.Search, stringResource(R.string.entries_no_matches), Modifier.padding(vertical = 32.dp))
                    }
                }
            }
        }
    }
}

/**
 * One word, read as one item by TalkBack. Words without a reading or comment get no empty line (Gboard allows an empty
 * shortcut).
 *
 * A plain segment rather than a ListItem: ListItem treats reading + comment as a three-line item with an 88dp minimum
 * and top-aligned content, which leaves a gap under short text (at smaller font sizes especially). Here the padding is
 * the same 12dp above and below whatever the row holds, centered within the one-line minimum height.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EntryRow(entry: EntryEntity, shapes: ListItemShapes, showLanguage: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = shapes.shape, color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                if (entry.reading.isNotEmpty()) Text(entry.reading, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Text(entry.word, style = MaterialTheme.typography.titleMediumEmphasized, color = colors.onSurface)
                if (entry.comment.isNotEmpty()) Text(entry.comment, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            // Same word and reading in two languages are two shared-dictionary words; the language label says which is which.
            if (entry.partOfSpeech.isNotEmpty() || showLanguage) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.partOfSpeech.isNotEmpty()) Label(entry.partOfSpeech, colors.surfaceContainerHighest, colors.onSurfaceVariant)
                    if (showLanguage) Label(languageName(entry.locale), colors.secondaryContainer, colors.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = Modifier.widthIn(max = 144.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** An icon in an Expressive shape over a short message: the empty, no-match and error states, and the detail placeholder. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShapeMessage(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Shown in the detail pane on wide screens before a source is chosen. */
@Composable
fun EntriesPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ShapeMessage(AppIcons.Books, stringResource(R.string.entries_placeholder))
    }
}
