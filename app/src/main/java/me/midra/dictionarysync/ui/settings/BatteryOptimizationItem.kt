package me.midra.dictionarysync.ui.settings

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.midra.dictionarysync.R
import me.midra.dictionarysync.sync.BackgroundAccess
import me.midra.dictionarysync.ui.common.AppIcons
import me.midra.dictionarysync.ui.common.toneColors
import me.midra.dictionarysync.ui.sources.Tone

/**
 * Battery optimization exemption with its current state; opens the system dialog or list. Shown in setup and settings.
 * With [shapes] it is one segment of a settings group; otherwise a plain row on [containerColor].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BatteryOptimizationItem(optimized: Boolean?, modifier: Modifier = Modifier, containerColor: Color = ListItemDefaults.containerColor,
    shapes: ListItemShapes? = null) {
    if (optimized == null) return
    val context = LocalContext.current
    val tone = if (optimized) Tone.Attention else Tone.Positive
    val onClick = { BackgroundAccess.openBatterySettings(context, optimized) }
    val leading = @Composable { Icon(AppIcons.Battery, contentDescription = null) }
    val supporting = @Composable {
        Text(stringResource(if (optimized) R.string.background_battery_on else R.string.background_battery_off), color = toneColors(tone).accent)
    }
    val trailing = @Composable { Icon(AppIcons.OpenInNew, contentDescription = null) }
    val headline = @Composable { Text(stringResource(R.string.background_battery)) }
    if (shapes != null) SegmentedListItem(onClick = onClick, shapes = shapes, modifier = modifier, colors = settingsItemColors(),
        leadingContent = leading, supportingContent = supporting, trailingContent = trailing, content = headline)
    else ListItem(onClick = onClick, modifier = modifier, colors = ListItemDefaults.colors(containerColor = containerColor),
        leadingContent = leading, supportingContent = supporting, trailingContent = trailing, content = headline)
}
