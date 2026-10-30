package me.midra.dictionarysync.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.midra.dictionarysync.R
import me.midra.dictionarysync.library.LibraryViewModel
import me.midra.dictionarysync.sync.SyncReadiness
import me.midra.dictionarysync.ui.common.AppIcons
import me.midra.dictionarysync.ui.diagnostics.StatusRow
import me.midra.dictionarysync.ui.diagnostics.Step
import me.midra.dictionarysync.ui.settings.BatteryOptimizationItem
import me.midra.dictionarysync.ui.settings.NotificationItem
import me.midra.dictionarysync.ui.sources.Tone

/** First-launch setup. Every step can be left for later: none of it is required to add or browse sources. */
@Composable
fun SetupScreen(model: LibraryViewModel, onOpenDiagnostics: () -> Unit, onFinish: () -> Unit) {
    val batteryOptimized by model.batteryOptimized.collectAsStateWithLifecycle()
    val notificationsEnabled by model.notificationsEnabled.collectAsStateWithLifecycle()
    val readiness by model.readiness.collectAsStateWithLifecycle()
    SetupContent(batteryOptimized, notificationsEnabled, readiness, model::refreshNotifications, onOpenDiagnostics, onFinish)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SetupContent(batteryOptimized: Boolean?, notificationsEnabled: Boolean?, readiness: SyncReadiness?,
    onNotificationsChanged: () -> Unit, onOpenDiagnostics: () -> Unit, onFinish: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val complete = batteryOptimized == false && notificationsEnabled == true && readiness == SyncReadiness.READY
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { MediumFlexibleTopAppBar(title = { Text(stringResource(R.string.setup_title)) }, scrollBehavior = scrollBehavior) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!complete) Text(stringResource(R.string.setup_later), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The one action of the screen, so a medium button: taller, with larger text.
                    val height = ButtonDefaults.MediumContainerHeight
                    Button(onClick = onFinish, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = height),
                        shapes = ButtonDefaults.shapesFor(height), contentPadding = ButtonDefaults.contentPaddingFor(height)) {
                        Text(stringResource(R.string.setup_start), style = ButtonDefaults.textStyleFor(height))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
                Step(1, stringResource(R.string.setup_step_background), done = batteryOptimized == false) {
                    Text(stringResource(R.string.setup_step_background_body))
                    // The card supplies the surface; the row reaches into the card padding so its text lines up with the body.
                    BatteryOptimizationItem(batteryOptimized, Modifier.bleed(16.dp), containerColor = Color.Transparent)
                }
                Step(2, stringResource(R.string.setup_step_notifications), done = notificationsEnabled == true) {
                    Text(stringResource(R.string.setup_step_notifications_body))
                    NotificationItem(notificationsEnabled, onNotificationsChanged, Modifier.bleed(16.dp), containerColor = Color.Transparent)
                }
                Step(3, stringResource(R.string.setup_step_dictionary), done = readiness == SyncReadiness.READY) {
                    Text(stringResource(R.string.setup_step_dictionary_body))
                    readiness?.let {
                        StatusRow(if (it == SyncReadiness.READY) Tone.Positive else Tone.Attention, stringResource(when (it) {
                            SyncReadiness.READY -> R.string.settings_diag_ready
                            SyncReadiness.IME_DISABLED -> R.string.settings_diag_ime
                            SyncReadiness.PROBE_UNFINISHED -> R.string.settings_diag_unfinished
                            SyncReadiness.UNSUPPORTED -> R.string.settings_diag_unsupported
                            SyncReadiness.DIAGNOSIS_REQUIRED -> R.string.settings_diag_required
                        }))
                    }
                    OutlinedButton(onClick = onOpenDiagnostics) {
                        Icon(AppIcons.Verified, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.setup_open_diagnostics))
                    }
                }
            }
        }
    }
}

/** Widens the content by [horizontal] on both sides, like a negative padding. */
private fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    val extra = horizontal.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * extra))
    layout(constraints.constrainWidth(placeable.width - 2 * extra), placeable.height) { placeable.place(-extra, 0) }
}
