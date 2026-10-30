package me.midra.dictionarysync.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.midra.dictionarysync.R
import me.midra.dictionarysync.library.LibraryViewModel
import me.midra.dictionarysync.sync.SyncReadiness
import me.midra.dictionarysync.ui.common.*
import me.midra.dictionarysync.ui.sources.Tone

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: LibraryViewModel, onOpenDiagnostics: () -> Unit) {
    val wifi by model.wifiOnly.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val readiness by model.readiness.collectAsStateWithLifecycle()
    val batteryOptimized by model.batteryOptimized.collectAsStateWithLifecycle()
    val notificationsEnabled by model.notificationsEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    var licenses by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { MediumFlexibleTopAppBar(title = { Text(stringResource(R.string.settings_title)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        // Grouped settings: each section is one rounded group of segments, kept to a readable width on wide screens.
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                item { SectionHeader(stringResource(R.string.settings_section_sync)) }
                item {
                    // One switch control for TalkBack: the whole row toggles, the Switch itself is decorative.
                    SegmentedListItem(onClick = { model.wifiOnly(!wifi) }, shapes = ListItemDefaults.segmentedShapes(0, 2), enabled = !busy,
                        colors = settingsItemColors(),
                        modifier = Modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(wifi) },
                        leadingContent = { Icon(AppIcons.Wifi, contentDescription = null) },
                        supportingContent = { Text(stringResource(R.string.settings_wifi_detail)) },
                        trailingContent = { Switch(checked = wifi, onCheckedChange = null, enabled = !busy) },
                    ) { Text(stringResource(R.string.settings_wifi)) }
                }
                item {
                    val tone = when (readiness) { SyncReadiness.READY -> Tone.Positive; null -> Tone.Neutral; else -> Tone.Attention }
                    SegmentedListItem(onClick = onOpenDiagnostics, shapes = ListItemDefaults.segmentedShapes(1, 2), colors = settingsItemColors(),
                        leadingContent = { Icon(AppIcons.Verified, contentDescription = null) },
                        supportingContent = {
                            readiness?.let {
                                Text(stringResource(when (it) {
                                    SyncReadiness.READY -> R.string.settings_diag_ready
                                    SyncReadiness.IME_DISABLED -> R.string.settings_diag_ime
                                    SyncReadiness.PROBE_UNFINISHED -> R.string.settings_diag_unfinished
                                    SyncReadiness.UNSUPPORTED -> R.string.settings_diag_unsupported
                                    SyncReadiness.DIAGNOSIS_REQUIRED -> R.string.settings_diag_required
                                }), color = toneColors(tone).accent.takeIf { tone != Tone.Neutral } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
                    ) { Text(stringResource(R.string.settings_diagnostics)) }
                }
                item { SectionHeader(stringResource(R.string.settings_section_background), Modifier.padding(top = 16.dp)) }
                item { BatteryOptimizationItem(batteryOptimized, shapes = ListItemDefaults.segmentedShapes(0, 2)) }
                item { NotificationItem(notificationsEnabled, model::refreshNotifications, shapes = ListItemDefaults.segmentedShapes(1, 2)) }
                item { SectionHeader(stringResource(R.string.settings_section_about), Modifier.padding(top = 16.dp)) }
                item {
                    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(0, 2), colors = settingsItemColors(),
                        leadingContent = { Icon(AppIcons.Info, contentDescription = null) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.settings_about_body))
                                Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.labelMedium)
                            }
                        }) { Text(stringResource(R.string.app_name)) }
                }
                item {
                    SegmentedListItem(onClick = { licenses = true }, shapes = ListItemDefaults.segmentedShapes(1, 2), colors = settingsItemColors(),
                        leadingContent = { Icon(AppIcons.Description, contentDescription = null) },
                        supportingContent = { Text(stringResource(R.string.settings_licenses_body)) },
                    ) { Text(stringResource(R.string.settings_licenses)) }
                }
            }
        }
    }
    if (licenses) LicenseDialog { licenses = false }
}

/** Segments sit on the screen's surface, so they take the next container tone up, like the source list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun settingsItemColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember { runCatching { context.assets.open("licenses/apache-2.0.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_licenses)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_licenses_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } })
}
