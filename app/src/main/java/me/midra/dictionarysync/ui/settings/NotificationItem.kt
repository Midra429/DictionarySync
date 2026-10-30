package me.midra.dictionarysync.ui.settings

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
 * Sync notifications with their current state. While off it asks for the permission, and opens the app's notification
 * settings once the system no longer shows the dialog; while on it opens those settings, where each channel can be turned off.
 * Shown in setup and settings. With [shapes] it is one segment of a settings group; otherwise a plain row on [containerColor].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NotificationItem(enabled: Boolean?, onChanged: () -> Unit, modifier: Modifier = Modifier,
    containerColor: Color = ListItemDefaults.containerColor, shapes: ListItemShapes? = null) {
    if (enabled == null) return
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onChanged()
        // Denied without a dialog: the user turned it off for good, so only the settings can turn it back on.
        if (!granted && (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false)
            BackgroundAccess.openNotificationSettings(context)
    }
    val onClick = {
        if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request.launch(Manifest.permission.POST_NOTIFICATIONS)
        else BackgroundAccess.openNotificationSettings(context)
    }
    val tone = if (enabled) Tone.Positive else Tone.Attention
    val leading = @Composable { Icon(AppIcons.Notifications, contentDescription = null) }
    val supporting = @Composable {
        Text(stringResource(if (enabled) R.string.background_notifications_on else R.string.background_notifications_off),
            color = toneColors(tone).accent)
    }
    val trailing = @Composable { Icon(AppIcons.OpenInNew, contentDescription = null) }
    val headline = @Composable { Text(stringResource(R.string.background_notifications)) }
    if (shapes != null) SegmentedListItem(onClick = onClick, shapes = shapes, modifier = modifier, colors = settingsItemColors(),
        leadingContent = leading, supportingContent = supporting, trailingContent = trailing, content = headline)
    else ListItem(onClick = onClick, modifier = modifier, colors = ListItemDefaults.colors(containerColor = containerColor),
        leadingContent = leading, supportingContent = supporting, trailingContent = trailing, content = headline)
}
