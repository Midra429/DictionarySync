package me.midra.dictionarysync.ui.common

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import java.text.NumberFormat
import me.midra.dictionarysync.R
import me.midra.dictionarysync.ui.sources.*

/** Container/content colors that carry a status tone. Always paired with an icon and text, never color alone. */
data class ToneColors(val container: Color, val content: Color, val accent: Color, val onAccent: Color)

@Composable
fun toneColors(tone: Tone): ToneColors {
    val c = MaterialTheme.colorScheme
    return when (tone) {
        Tone.Positive -> ToneColors(c.primaryContainer, c.onPrimaryContainer, c.primary, c.onPrimary)
        Tone.Working -> ToneColors(c.secondaryContainer, c.onSecondaryContainer, c.secondary, c.onSecondary)
        Tone.Attention -> ToneColors(c.tertiaryContainer, c.onTertiaryContainer, c.tertiary, c.onTertiary)
        Tone.Problem -> ToneColors(c.errorContainer, c.onErrorContainer, c.error, c.onError)
        Tone.Neutral -> ToneColors(c.surfaceContainerHighest, c.onSurfaceVariant, c.onSurfaceVariant, c.surface)
    }
}

fun toneIcon(tone: Tone): ImageVector = when (tone) {
    Tone.Positive -> AppIcons.CheckCircle
    Tone.Working -> AppIcons.Schedule
    Tone.Attention -> AppIcons.Warning
    Tone.Problem -> AppIcons.Error
    Tone.Neutral -> AppIcons.Info
}

@Composable
fun reasonText(code: String): String {
    LocalConfiguration.current // Re-read on configuration changes, as stringResource does.
    return reasonText(LocalContext.current, code)
}

/** [reasonText] outside composition, for notifications. */
fun reasonText(context: Context, code: String): String = when (reasonOf(code)) {
    Reason.Diagnosis -> context.getString(R.string.reason_diagnosis)
    Reason.ImeDisabled -> context.getString(R.string.reason_ime_disabled)
    Reason.PlanFirst -> context.getString(R.string.reason_plan_first)
    Reason.Refetch -> context.getString(R.string.reason_refetch)
    Reason.External -> context.getString(R.string.reason_external)
    Reason.Unknown -> context.getString(R.string.reason_unknown)
    Reason.DeleteFailed -> context.getString(R.string.reason_delete_failed)
    Reason.Stale -> context.getString(R.string.reason_stale)
    Reason.Incomplete -> context.getString(R.string.reason_incomplete)
    Reason.Wifi -> context.getString(R.string.reason_wifi)
    Reason.Network -> context.getString(R.string.reason_network)
    Reason.ServerWait -> context.getString(R.string.reason_server_wait)
    Reason.CsvManual -> context.getString(R.string.reason_csv_manual)
    Reason.Columns -> context.getString(R.string.reason_columns)
    Reason.Unsupported -> context.getString(R.string.reason_unsupported)
    Reason.Format -> context.getString(R.string.reason_format)
    Reason.Encoding -> context.getString(R.string.reason_encoding)
    Reason.Size -> context.getString(R.string.reason_size)
    Reason.Http -> context.getString(R.string.reason_http, code.removePrefix("HTTP_").takeWhile(Char::isDigit))
    Reason.Url -> context.getString(R.string.reason_url)
    Reason.Duplicate -> context.getString(R.string.reason_duplicate)
    Reason.Name -> context.getString(R.string.reason_name)
    Reason.Locale -> context.getString(R.string.reason_locale)
    Reason.Empty -> context.getString(R.string.reason_empty)
    Reason.Generic -> context.getString(R.string.reason_generic)
}

/** A resolved language tag in the app's (Japanese) UI language; "ALL" is every language. Never guesses beyond the tag. */
@Composable
fun languageName(tag: String): String = if (tag == "ALL") stringResource(R.string.choice_all)
    else java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.JAPANESE).ifEmpty { tag }
        .replace(" (", "（").replace(")", "）") // Match the full-width brackets used elsewhere in the UI.

@Composable
fun statusText(status: SourceStatus): String = when (status.kind) {
    StatusKind.Synced -> stringResource(R.string.status_synced)
    StatusKind.Fetching -> stringResource(R.string.status_fetching)
    StatusKind.Queued -> stringResource(R.string.status_queued)
    StatusKind.NetworkWait -> stringResource(R.string.status_network_wait)
    StatusKind.ServerWait -> stringResource(R.string.status_server_wait, dateTime(status.until ?: 0))
    StatusKind.RetryWait -> stringResource(R.string.status_retry_wait, dateTime(status.until ?: 0))
    StatusKind.Reflecting -> stringResource(R.string.status_reflecting)
    StatusKind.ReflectPending -> stringResource(R.string.status_reflect_pending)
    StatusKind.ReflectQueued -> stringResource(R.string.status_reflect_queued)
    StatusKind.Deleting -> stringResource(R.string.status_deleting)
    StatusKind.ReflectFailed -> stringResource(R.string.status_reflect_failed)
    StatusKind.Interrupted -> stringResource(R.string.status_interrupted)
    StatusKind.FetchFailed -> stringResource(R.string.status_fetch_failed)
    StatusKind.FetchInterrupted -> stringResource(R.string.status_fetch_interrupted)
    StatusKind.Disabled -> stringResource(R.string.status_disabled)
    StatusKind.ReleasePending -> stringResource(R.string.status_release_pending)
    StatusKind.DeletePending -> stringResource(R.string.status_delete_pending)
    StatusKind.NotFetched -> stringResource(R.string.status_not_fetched)
    StatusKind.Reverted -> stringResource(R.string.status_reverted)
}

@Composable
fun updatedText(value: UpdatedAt): String = when (value) {
    UpdatedAt.Never -> stringResource(R.string.source_updated_never)
    UpdatedAt.WithinHour -> stringResource(R.string.source_updated_within_hour)
    is UpdatedAt.HoursAgo -> stringResource(R.string.source_updated_hours, value.hours)
    is UpdatedAt.On -> stringResource(R.string.source_updated_date, value.date)
}

@Composable
fun intervalText(hours: Int): String =
    if (hours >= 72 && hours % 24 == 0) stringResource(R.string.interval_days, hours / 24) else stringResource(R.string.interval_hours, hours)

fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)

/**
 * Icon + one line of status text in the tone's accent color. With [detail], a second line explains a problem, and a
 * failed or interrupted fetch says that the previous data is still in use.
 */
@Composable
fun StatusLine(status: SourceStatus, modifier: Modifier = Modifier, detail: Boolean = true) {
    val colors = toneColors(status.tone)
    // The icon follows the text size, so it stays in proportion with large fonts.
    val iconSize = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(toneIcon(status.tone), contentDescription = null, tint = colors.accent, modifier = Modifier.size(iconSize))
            Text(statusText(status), style = MaterialTheme.typography.labelLarge, color = if (status.tone == Tone.Neutral) colors.content else colors.accent)
        }
        if (!detail) return@Column
        val lines = listOfNotNull(
            status.reason?.takeIf { status.tone == Tone.Problem || status.tone == Tone.Attention }?.let { reasonText(it) },
            if (status.kind == StatusKind.FetchFailed || status.kind == StatusKind.FetchInterrupted) stringResource(R.string.status_previous_data) else null,
        )
        if (lines.isNotEmpty()) Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = iconSize + 6.dp))
    }
}

/** Prominent, actionable notice above a list. The action is a filled button in the tone's accent, so it reads as a button. */
@Composable
fun Banner(tone: Tone, title: String, body: String?, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null, enabled: Boolean = true) {
    val colors = toneColors(tone)
    Surface(color = colors.container, contentColor = colors.content, shape = MaterialTheme.shapes.largeIncreased, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(toneIcon(tone), contentDescription = null, tint = colors.accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.semantics { heading() })
                    body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (action != null && onAction != null) Button(onClick = onAction, enabled = enabled, modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)) { Text(action) }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
}

/**
 * Full-screen dialog drawn edge to edge. The dialog has its own window, so its system bar icons are set here to match the
 * theme; otherwise they keep the platform default and can disappear against a light surface.
 */
@Composable
fun FullScreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
        content()
    }
}

/** Error dialog with a short explanation and the internal code for support. */
@Composable
fun ErrorDialog(code: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Error, contentDescription = null) },
        title = { Text(stringResource(R.string.error_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reasonText(code))
                Text(stringResource(R.string.error_code, code), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } })
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().padding(32.dp))
}


/** Icon-only action with a plain tooltip on long press / hover; the label is also its content description. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below), tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
    }
}

/**
 * Search field styled as an M3 search bar (filled pill, placeholder instead of a floating label). It filters content in
 * place, so it stays a plain field rather than a search bar that expands into its own results view.
 */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    val focus = LocalFocusManager.current
    val container = containerColor
    TextField(query, onQueryChange, singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else ({ TooltipIconButton(AppIcons.Close, stringResource(R.string.entries_clear_search), { onQueryChange("") }) }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = SearchBarDefaults.inputFieldShape,
        colors = TextFieldDefaults.colors(focusedContainerColor = container, unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        modifier = modifier.fillMaxWidth().heightIn(min = SearchBarDefaults.InputFieldHeight))
}
