package me.midra.dictionarysync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.midra.dictionarysync.R
import me.midra.dictionarysync.data.source.SourceRules
import me.midra.dictionarysync.ui.common.AppIcons
import me.midra.dictionarysync.ui.common.ErrorDialog
import me.midra.dictionarysync.ui.editor.SourceEditorDialog
import me.midra.dictionarysync.ui.entries.EntriesPlaceholder
import me.midra.dictionarysync.ui.entries.EntriesScreen
import me.midra.dictionarysync.ui.review.SyncReviewDialog
import me.midra.dictionarysync.ui.settings.SettingsScreen
import me.midra.dictionarysync.ui.setup.SetupScreen
import me.midra.dictionarysync.ui.sources.SourceListScreen

@Serializable data object SourcesRoute : NavKey
@Serializable data class EntriesRoute(val sourceId: String) : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data object DiagnosticsRoute : NavKey
/** First-launch setup; only ever the bottom of the back stack, replaced by [SourcesRoute] when finished. */
@Serializable data object SetupRoute : NavKey

/**
 * Two top-level destinations (sources, settings). Compact width: short navigation bar on top-level screens.
 * Medium and wider: wide navigation rail with the add-source button on top, and the word list opens beside the source list (expanded width) with a
 * drag handle between them that snaps to a narrow list, an even split or a narrow word list.
 * With [setup], starts at the first-launch setup, which has no navigation bar or rail.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DictionarySyncApp(model: AppViewModel, setup: Boolean = false, diagnostics: @Composable (onBack: () -> Unit) -> Unit) {
    val backStack = rememberNavBackStack(if (setup) SetupRoute else SourcesRoute)
    val inSetup = backStack.first() == SetupRoute
    val inSettings = !inSetup && backStack.any { it == SettingsRoute || it == DiagnosticsRoute }
    fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    fun showSources() { while (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    fun showSettings() { if (!inSettings) { showSources(); backStack.add(SettingsRoute) } }
    fun openDiagnostics() {
        if (!inSetup) showSettings()
        if (backStack.last() != DiagnosticsRoute) backStack.add(DiagnosticsRoute)
    }
    fun finishSetup() { model.settings.finishSetup(); showSources(); backStack[0] = SourcesRoute }
    // Shared by the list's FAB and the rail's, so the limit message shows on the source list either way.
    val sources by model.sources.sources.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val limitMessage = stringResource(R.string.sources_limit_reached)
    fun addSource() {
        if (inSettings) showSources()
        if (sources.size >= SourceRules.MAX_SOURCES) scope.launch { snackbar.showSnackbar(limitMessage) } else model.form.edit(null)
    }
    // Both can change in system settings while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.settings.refreshAll() }
    // Diagnostics runs inside this activity, so finishing it and going back does not resume anything.
    val top = backStack.last()
    LaunchedEffect(top) { if (top != DiagnosticsRoute) model.settings.refreshReadiness() }

    val transitions = rememberNavTransitions()
    val paneExpansion = rememberPaneExpansionState(anchors = PaneAnchors, initialAnchoredIndex = 1)
    // Back closes the word list beside the sources first; the default would skip it (both panes stay shown) and leave the app.
    val listDetail = rememberListDetailSceneStrategy<NavKey>(backNavigationBehavior = BackNavigationBehavior.PopUntilContentChange, paneExpansionState = paneExpansion, paneExpansionDragHandle = { state ->
        val interaction = remember { MutableInteractionSource() }
        VerticalDragHandle(Modifier.paneExpansionDraggable(state, LocalMinimumInteractiveComponentSize.current, interaction),
            interactionSource = interaction)
    })

    // One surface behind the rail, both panes and the gap between them, so the panes read as one screen.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val wide = maxWidth >= 600.dp && !inSetup
        val topLevel = backStack.last() == SourcesRoute || backStack.last() == SettingsRoute
        val showBar = !wide && topLevel
        Row(Modifier.fillMaxSize()) {
            if (wide) WideNavigationRail(header = { AddSourceRailButton(::addSource) }) {
                DestinationItems(inSettings, ::showSources, ::showSettings) { selected, onClick, icon, label ->
                    WideNavigationRailItem(selected = selected, onClick = onClick, icon = icon, label = label, railExpanded = false)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val opened = (backStack.last() as? EntriesRoute)?.sourceId
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.weight(1f).then(
                        if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        else if (wide) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier),
                    onBack = ::pop,
                    sceneStrategies = listOf(listDetail),
                    // Fade through between top-level destinations; screens opened from another override it in their metadata.
                    transitionSpec = { transitions.fadeThrough(initialState, targetState) },
                    popTransitionSpec = { transitions.fadeThrough(initialState, targetState) },
                    predictivePopTransitionSpec = { edge -> transitions.predictiveBack(edge) },
                    entryProvider = entryProvider {
                        entry<SourcesRoute>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { EntriesPlaceholder() })) {
                            SourceListScreen(model, opened, snackbar, onAdd = ::addSource, showFab = !wide,
                                onOpenSource = { id ->
                                    if (backStack.last() is EntriesRoute) backStack.removeAt(backStack.lastIndex)
                                    backStack.add(EntriesRoute(id))
                                },
                                onOpenDiagnostics = ::openDiagnostics)
                        }
                        entry<EntriesRoute>(metadata = ListDetailSceneStrategy.detailPane() + transitions.hierarchical()) { route ->
                            EntriesScreen(model, route.sourceId, onBack = {
                                if (backStack.lastOrNull() == route) backStack.removeAt(backStack.lastIndex)
                            })
                        }
                        entry<SettingsRoute> { SettingsScreen(model.settings, model.operations, onOpenDiagnostics = ::openDiagnostics) }
                        entry<DiagnosticsRoute>(metadata = transitions.hierarchical()) { diagnostics(::pop) }
                        entry<SetupRoute> { SetupScreen(model.settings, onOpenDiagnostics = ::openDiagnostics, onFinish = ::finishSetup) }
                    },
                )
                if (showBar) ShortNavigationBar {
                    DestinationItems(inSettings, ::showSources, ::showSettings) { selected, onClick, icon, label ->
                        ShortNavigationBarItem(selected = selected, onClick = onClick, icon = icon, label = label)
                    }
                }
            }
        }
    }
    Overlays(model)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val PaneAnchors = listOf(
    PaneExpansionAnchor.Offset.fromStart(360.dp), PaneExpansionAnchor.Proportion(0.5f), PaneExpansionAnchor.Offset.fromEnd(360.dp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSourceRailButton(onClick: () -> Unit) {
    val label = stringResource(R.string.action_add_source)
    TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End), tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()) {
        // Lowered: a FAB in a rail sits on the rail rather than floating over content. The rail places its header at the
        // start edge, so the padding centers the 56dp button in the 96dp collapsed rail.
        FloatingActionButton(onClick = onClick, modifier = Modifier.padding(start = 20.dp),
            elevation = FloatingActionButtonDefaults.loweredElevation()) {
            Icon(AppIcons.Add, contentDescription = label)
        }
    }
}

@Composable
private fun DestinationItems(inSettings: Boolean, showSources: () -> Unit, showSettings: () -> Unit,
    item: @Composable (Boolean, () -> Unit, @Composable () -> Unit, @Composable () -> Unit) -> Unit) {
    item(!inSettings, showSources, { Icon(painterResource(R.drawable.ic_format_list_bulleted_24), contentDescription = null) },
        { Text(stringResource(R.string.nav_sources)) })
    item(inSettings, showSources@{ showSettings() }, { Icon(painterResource(R.drawable.ic_settings_24), contentDescription = null) },
        { Text(stringResource(R.string.nav_settings)) })
}

/** Dialog-level flows driven by ViewModel state, so they survive recreation and finish even if the caller is gone. */
@Composable
private fun Overlays(model: AppViewModel) {
    val sources by model.sources.sources.collectAsStateWithLifecycle()
    val formOpen by model.form.open.collectAsStateWithLifecycle()
    val editingId by model.form.editingId.collectAsStateWithLifecycle()
    val retryAt by model.form.retryAt.collectAsStateWithLifecycle()
    val saving by model.form.saving.collectAsStateWithLifecycle()
    val busy by model.operations.busy.collectAsStateWithLifecycle()
    val error by model.operations.error.collectAsStateWithLifecycle()
    val preview by model.review.preview.collectAsStateWithLifecycle()
    val review = model.review
    preview?.let { SyncReviewDialog(it, busy, sources, review::dismiss, review::cancel, review::apply, review::prepareRecovery) }
    if (formOpen) SourceEditorDialog(sources.find { it.id == editingId }, busy, saving, retryAt, error, model.operations::dismissError,
        model.form::cancel, model.form::dismiss, model.form::save)
    // Form errors are shown inline in the form; everything else as a dialog.
    if (!formOpen) error?.let { ErrorDialog(it, model.operations::dismissError) }
}
