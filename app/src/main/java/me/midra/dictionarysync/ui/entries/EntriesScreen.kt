package me.midra.dictionarysync.ui.entries

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.LocalListDetailSceneScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import me.midra.dictionarysync.R
import me.midra.dictionarysync.ui.AppViewModel
import me.midra.dictionarysync.ui.common.*

/** Read-only word list of the latest fetched generation (detail pane). */
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
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(source?.name.orEmpty(), maxLines = 1) },
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
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("dictionary-entries"), contentPadding = PaddingValues(bottom = 24.dp)) {
            snapshot?.takeIf { it.skippedLines > 0 }?.let {
                item(key = "skipped") {
                    Text(stringResource(R.string.entries_skipped_lines, it.skippedLines, it.firstSkippedLine ?: 0),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("entries-skipped"))
                }
            }
            item(key = "search") {
                SearchField(query, state::search, stringResource(R.string.entries_search),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("entries-search"))
            }
            items(entries.itemCount, key = entries.itemKey { "${it.snapshotId}:${it.line}" }) { index ->
                entries[index]?.let { entry ->
                    val details = listOf(entry.reading, entry.partOfSpeech).filter { it.isNotEmpty() }.joinToString(" · ")
                    ListItem(
                        headlineContent = { Text(entry.word, style = MaterialTheme.typography.titleMedium) },
                        // No empty line for a word without reading or comment (Gboard allows an empty shortcut).
                        supportingContent = if (details.isEmpty() && entry.comment.isEmpty()) null else ({
                            Column {
                                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodyMedium)
                                if (entry.comment.isNotEmpty()) Text(entry.comment, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }),
                        // Same word and reading in two languages are two shared-dictionary words; say which is which.
                        trailingContent = if (!mixed) null else ({ LanguageLabel(entry.locale) }),
                    )
                }
            }
            item(key = "state") {
                when (val refresh = entries.loadState.refresh) {
                    is LoadState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        val loading = stringResource(R.string.entries_loading)
                        LoadingIndicator(Modifier.semantics { contentDescription = loading })
                    }
                    is LoadState.Error -> CenteredMessage(stringResource(R.string.entries_load_error) + "\n" +
                        stringResource(R.string.error_code, refresh.error.javaClass.simpleName))
                    else -> if (entries.itemCount == 0 && snapshot != null)
                        CenteredMessage(stringResource(if (query.isEmpty()) R.string.entries_empty else R.string.entries_no_matches))
                }
            }
        }
    }
}

@Composable
private fun LanguageLabel(tag: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small, modifier = Modifier.widthIn(max = 160.dp)) {
        Text(languageName(tag), style = MaterialTheme.typography.labelMedium, maxLines = 2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Shown in the detail pane on wide screens before a source is chosen. Same shape language as the empty source list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EntriesPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center) {
                Icon(AppIcons.Books, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
            }
            Text(stringResource(R.string.entries_placeholder), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
