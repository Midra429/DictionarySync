package me.midra.dictionarysync.ui.diagnostics

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.midra.dictionarysync.R
import me.midra.dictionarysync.probe.ProbeUiState
import me.midra.dictionarysync.probe.ProbeViewModel
import me.midra.dictionarysync.ui.common.*
import me.midra.dictionarysync.ui.sources.Tone

/** Three-step device diagnosis. Each step's indicator morphs from a circle to a cookie when it is done. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(viewModel: ProbeViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenImeSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiagnosticsContent(state, onBack, onOpenSettings, onOpenImeSettings, onRead = viewModel::read, onWrite = viewModel::runBasic,
        onInspect = viewModel::inspectTrial, onCleanup = viewModel::cleanupTrial)
}

/** Stateless diagnostics content, so it can be previewed and tested without the Provider. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsContent(state: ProbeUiState, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenImeSettings: () -> Unit,
    onRead: (Boolean) -> Unit, onWrite: () -> Unit, onInspect: () -> Unit, onCleanup: () -> Unit) {
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var confirmWrite by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val readDone = state.readConfirmed || state.certified
    LaunchedEffect(state.statusStep, state.status, state.busy) {
        if (!state.busy && state.statusStep != 0) haptics.performHapticFeedback(
            if (state.status in setOf(R.string.probe_read_confirmed, R.string.probe_trial_done)) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = { TooltipIconButton(AppIcons.ArrowBack, stringResource(R.string.action_back), onBack) },
                scrollBehavior = scrollBehavior)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.diag_intro), style = MaterialTheme.typography.bodyLarge)
                if (state.certified) Banner(Tone.Positive, stringResource(R.string.diag_ready), null)

                Step(1, stringResource(R.string.diag_step_ime), done = state.imeEnabled) {
                    Text(stringResource(R.string.diag_step_ime_body))
                    StatusRow(if (state.imeEnabled) Tone.Positive else Tone.Attention,
                        stringResource(if (state.imeEnabled) R.string.diag_ime_on else R.string.diag_ime_off))
                    OutlinedButton(onClick = onOpenImeSettings, enabled = !state.busy) {
                        Icon(AppIcons.Keyboard, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.diag_open_ime))
                    }
                }

                Step(2, stringResource(R.string.diag_step_read), done = readDone) {
                    Text(stringResource(R.string.diag_step_read_body))
                    Marker(state.marker)
                    Text(stringResource(R.string.diag_marker_keep), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOpenSettings, enabled = !state.busy) {
                        Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.diag_open_dictionary))
                    }
                    Row(Modifier.fillMaxWidth().toggleable(confirmed, role = Role.Checkbox, onValueChange = { confirmed = it }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = null)
                        Text(stringResource(R.string.diag_confirm_marker), Modifier.padding(start = 12.dp))
                    }
                    Button(onClick = { onRead(confirmed) }, enabled = !state.busy && state.marker.isNotEmpty()) { Text(stringResource(R.string.diag_run_read)) }
                    Result(state.statusStep == 2, state.busy, state.status)
                }

                Step(3, stringResource(R.string.diag_step_write), done = state.certified) {
                    Text(stringResource(R.string.diag_step_write_body))
                    if (!state.readConfirmed && !state.certified) Text(stringResource(R.string.diag_write_needs_read),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { confirmWrite = true }, enabled = !state.busy && state.readConfirmed && !state.trialUnfinished) {
                        Text(stringResource(R.string.diag_run_write))
                    }
                    Result(state.statusStep == 3, state.busy, state.status)
                    if (state.trialUnfinished) {
                        Banner(Tone.Attention, stringResource(R.string.diag_unfinished), null)
                        OutlinedButton(onClick = onInspect, enabled = !state.busy) { Text(stringResource(R.string.diag_inspect)) }
                        OutlinedButton(onClick = onCleanup, enabled = !state.busy) { Text(stringResource(R.string.diag_cleanup)) }
                    }
                }

                if (state.report.isNotEmpty() || state.trialReport.isNotEmpty()) {
                    TextButton(onClick = { showLog = !showLog }) {
                        Icon(if (showLog) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(if (showLog) R.string.diag_hide_log else R.string.diag_show_log))
                    }
                    AnimatedVisibility(showLog) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.diag_log_note), style = MaterialTheme.typography.labelLarge)
                            SelectionContainer {
                                Text(listOf(state.report, state.trialReport).filter { it.isNotEmpty() }.joinToString("\n\n"),
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmWrite) AlertDialog(onDismissRequest = { confirmWrite = false },
        title = { Text(stringResource(R.string.diag_write_confirm_title)) },
        text = { Text(stringResource(R.string.diag_write_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirmWrite = false; onWrite() }) { Text(stringResource(R.string.diag_begin)) } },
        dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Step(number: Int, title: String, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val progress by animateFloatAsState(if (done) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "step")
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val colors = MaterialTheme.colorScheme
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(44.dp).background(if (done) colors.primary else colors.secondaryContainer, MorphShape(morph, progress)),
                    contentAlignment = Alignment.Center) {
                    if (done) Icon(AppIcons.Check, contentDescription = stringResource(R.string.diag_done), tint = colors.onPrimary)
                    else Text(number.toString(), style = MaterialTheme.typography.titleMediumEmphasized, color = colors.onSecondaryContainer)
                }
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f).semantics { heading() })
            }
            content()
        }
    }
}

/** Shape for a [Morph] between two normalized Material shapes at [progress]. */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    private val matrix = Matrix()
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        matrix.reset(); matrix.scale(size.width, size.height)
        val path = morph.toPath(progress)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

@Composable
internal fun StatusRow(tone: Tone, text: String) {
    val colors = toneColors(tone)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(toneIcon(tone), contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.accent)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Result(visible: Boolean, busy: Boolean, status: Int) {
    when {
        busy && visible -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator(Modifier.size(32.dp)); Text(stringResource(R.string.probe_loading))
        }
        visible -> {
            val ok = status in setOf(R.string.probe_read_confirmed, R.string.probe_trial_done)
            Surface(color = toneColors(if (ok) Tone.Positive else Tone.Problem).container, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(toneIcon(if (ok) Tone.Positive else Tone.Problem), contentDescription = null)
                    Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Marker(marker: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2_000); copied = false } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(marker.ifEmpty { stringResource(R.string.probe_loading) }, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            }
            TooltipIconButton(if (copied) AppIcons.Check else AppIcons.Copy, stringResource(if (copied) R.string.diag_copied else R.string.diag_copy),
                onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("marker", marker))); copied = true } },
                enabled = marker.isNotEmpty())
        }
    }
}
