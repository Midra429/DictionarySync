package me.midra.dictionarysync.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.midra.dictionarysync.R
import me.midra.dictionarysync.data.local.SourceEntity
import me.midra.dictionarysync.ui.common.*
import me.midra.dictionarysync.ui.sources.Reason
import me.midra.dictionarysync.data.parser.DictionaryParser
import me.midra.dictionarysync.data.source.SourceRules
import me.midra.dictionarysync.library.SourceDraft
import me.midra.dictionarysync.ui.sources.Tone
import me.midra.dictionarysync.ui.sources.dateTime
import me.midra.dictionarysync.ui.sources.reasonOf

internal val FORMATS = DictionaryParser.formats.toList()
internal val LANGUAGES = listOf("AUTO", "ja", "en", "ALL", "CUSTOM")
internal val ENCODINGS = listOf("AUTO") + DictionaryParser.ENCODINGS
internal val INTERVALS = SourceRules.intervals
private val MENU_INSET = 4.dp
private val ITEM_SPACING = 2.dp
private val HEADER_INSET = 12.dp
private val INTERVAL_PADDING = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
private val FORMAT_REASONS = setOf(Reason.Format, Reason.Columns, Reason.CsvManual, Reason.Unsupported)
private val OPTION_REASONS = FORMAT_REASONS + setOf(Reason.Locale, Reason.Encoding)

/** Draft input of the form, kept outside the dialog window so it survives recreation and process death. */
@Stable
class SourceDraftInput(name: MutableState<String>, url: MutableState<String>, locale: MutableState<String>, format: MutableState<String>,
    encoding: MutableState<String>, interval: MutableIntState, manualLanguage: MutableState<Boolean>) {
    var name by name
    var url by url
    var locale by locale
    var format by format
    var encoding by encoding
    var interval by interval
    var manualLanguage by manualLanguage
    fun draft() = SourceDraft(name, url, locale, format, encoding, interval)
}

@Composable
fun rememberSourceDraftInput(source: SourceEntity?): SourceDraftInput {
    val name = rememberSaveable(source?.id) { mutableStateOf(source?.name ?: "") }
    val url = rememberSaveable(source?.id) { mutableStateOf(source?.url ?: "") }
    val locale = rememberSaveable(source?.id) { mutableStateOf(source?.locale ?: "AUTO") }
    val format = rememberSaveable(source?.id) { mutableStateOf(source?.format ?: "AUTO") }
    val encoding = rememberSaveable(source?.id) { mutableStateOf(source?.encoding ?: "AUTO") }
    val interval = rememberSaveable(source?.id) { mutableIntStateOf(source?.syncIntervalHours ?: 24) }
    val manual = rememberSaveable(source?.id) { mutableStateOf(locale.value !in setOf("AUTO", "ja", "en", "ALL")) }
    return remember(name, url, locale, format, encoding, interval, manual) { SourceDraftInput(name, url, locale, format, encoding, interval, manual) }
}

/** Full-screen add/edit form. Input survives recreation; nothing is saved until the fetch and parse succeed. */
@Composable
fun SourceEditorDialog(source: SourceEntity?, busy: Boolean, fetching: Boolean, retryAt: Long, error: String?, onDismissError: () -> Unit,
    onStop: () -> Unit, onDismiss: () -> Unit, save: (SourceDraft) -> Unit) {
    val state = rememberSourceDraftInput(source)
    // Back while fetching stops the fetch and keeps the form; otherwise it closes the form.
    Dialog(onDismissRequest = { if (fetching) onStop() else if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        SourceForm(source, busy, onDismiss, retryAt, error, onDismissError, state, fetching, onStop, save)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SourceForm(source: SourceEntity?, busy: Boolean, onDismiss: () -> Unit, retryAt: Long = 0, error: String? = null,
    onDismissError: () -> Unit = {}, state: SourceDraftInput = rememberSourceDraftInput(source),
    fetching: Boolean = busy, onStop: () -> Unit = {}, save: (SourceDraft) -> Unit) {
    var name by state::name
    var url by state::url
    var locale by state::locale
    var format by state::format
    var encoding by state::encoding
    var interval by state::interval
    var manualLanguage by state::manualLanguage
    val reason = error?.let(::reasonOf)
    val canSave = !busy && name.isNotBlank() && url.isNotBlank()
    // Adding usually needs only a name and URL, so the auto-detected options start closed; editing shows them.
    var showOptions by rememberSaveable(source?.id) { mutableStateOf(source != null) }
    LaunchedEffect(reason) { if (reason in OPTION_REASONS) showOptions = true }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (source == null) R.string.editor_add_title else R.string.editor_edit_title)) },
                navigationIcon = {
                    if (fetching) IconButton(onClick = onStop) { Icon(AppIcons.Close, stringResource(R.string.editor_stop)) }
                    else IconButton(onClick = onDismiss, enabled = !busy) { Icon(AppIcons.Close, stringResource(R.string.action_cancel)) }
                },
                actions = {
                    Button(onClick = { save(state.draft()) }, enabled = canSave,
                        modifier = Modifier.padding(end = 8.dp)) {
                        Text(stringResource(if (source == null) R.string.editor_add else R.string.editor_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (fetching) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContainedLoadingIndicator(Modifier.size(40.dp))
                    Text(stringResource(R.string.editor_fetching), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.editor_stop)) }
                }
                if (error != null && !busy) Banner(Tone.Problem, reasonText(error), stringResource(R.string.error_code, error),
                    action = stringResource(R.string.action_close), onAction = onDismissError)
                if (retryAt > System.currentTimeMillis()) Text(stringResource(R.string.editor_server_wait, dateTime(retryAt)),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.editor_name)) }, enabled = !busy,
                    singleLine = true, isError = reason == Reason.Name,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.editor_url)) }, enabled = !busy,
                    singleLine = true, isError = reason in setOf(Reason.Url, Reason.Duplicate),
                    supportingText = { Text(stringResource(R.string.editor_url_support)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) save(state.draft()) }),
                    modifier = Modifier.fillMaxWidth())
                if (source != null) Text(stringResource(R.string.editor_keep_note), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Sections start 24dp below the previous content and keep their heading 12dp from what it labels.
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.editor_interval), style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.semantics { heading() })
                    IntervalGroup(interval, !busy) { interval = it }
                }

                Column(Modifier.padding(top = 8.dp)) {
                    OptionsHeader(showOptions, stringResource(R.string.editor_options_summary, choiceText(format),
                        choiceText(locale), choiceText(encoding))) { showOptions = !showOptions }
                    AnimatedVisibility(showOptions) {
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SelectField(stringResource(R.string.editor_format), format, FORMATS, !busy, reason in FORMAT_REASONS, hints = formatHints()) { format = it }
                            SelectField(stringResource(R.string.editor_language), if (manualLanguage) "CUSTOM" else locale, LANGUAGES, !busy, reason == Reason.Locale,
                                note = if (locale == "AUTO") stringResource(R.string.editor_language_auto_note) else null) {
                                manualLanguage = it == "CUSTOM"; locale = if (manualLanguage) "ja" else it
                            }
                            if (manualLanguage) OutlinedTextField(locale, { locale = it }, label = { Text(stringResource(R.string.editor_language_tag)) },
                                enabled = !busy, singleLine = true, isError = reason == Reason.Locale, modifier = Modifier.fillMaxWidth())
                            SelectField(stringResource(R.string.editor_encoding), encoding, ENCODINGS, !busy, reason == Reason.Encoding,
                                hints = mapOf("AUTO" to stringResource(R.string.hint_encoding_auto))) { encoding = it }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Connected toggle buttons; on narrow screens or large text the remaining choices move to the overflow menu.
 *
 * Weighted items would always be squeezed into the row (ButtonGroup never overflows them), so each button instead gets
 * an equal share of the row as its minimum width: the row fills while every label fits, and overflows once one doesn't.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IntervalGroup(selected: Int, enabled: Boolean, choose: (Int) -> Unit) {
    val labels = INTERVALS.associateWith { intervalText(it) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = with(density) { ButtonGroupDefaults.ConnectedSpaceBetween.roundToPx() }
        val space = constraints.maxWidth - spacing * (INTERVALS.size - 1)
        // Whole pixels, with the remainder going to the first buttons, so the row ends exactly at the fields' edge.
        val shares = INTERVALS.indices.map { with(density) { (space / INTERVALS.size + if (it < space % INTERVALS.size) 1 else 0).toDp() } }
        // No fillMaxWidth: the shares already add up to the row, and a minimum width breaks ButtonGroup's overflow measuring.
        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it, enabled = enabled) },
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            INTERVALS.forEachIndexed { index, hours ->
                // A custom item, because toggleableItem's fixed side padding is wider than these labels leave room for and
                // pushes them off center.
                customItem(
                    buttonGroupContent = {
                        val interactionSource = remember { MutableInteractionSource() }
                        ToggleButton(checked = hours == selected, onCheckedChange = { choose(hours) }, enabled = enabled,
                            interactionSource = interactionSource, contentPadding = INTERVAL_PADDING,
                            modifier = Modifier.widthIn(min = shares[index]).animateWidth(interactionSource, INTERVAL_PADDING.calculateEndPadding(LayoutDirection.Ltr))) {
                            Text(labels.getValue(hours), maxLines = 1, softWrap = false)
                        }
                    },
                    menuContent = { state ->
                        DropdownMenuItem(text = { Text(labels.getValue(hours)) }, enabled = enabled,
                            trailingIcon = { if (hours == selected) Icon(AppIcons.Check, contentDescription = null) },
                            modifier = Modifier.semantics { this.selected = hours == selected },
                            onClick = { choose(hours); state.dismiss() })
                    },
                )
            }
        }
    }
}

/**
 * Heading row that opens and closes the options; while closed it summarizes the current choices. The pressed area extends
 * [HEADER_INSET] beyond the text on every side, so the text lines up with the fields and other headings yet has room
 * inside the ripple.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptionsHeader(expanded: Boolean, summary: String, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().bleed(HEADER_INSET).clip(MaterialTheme.shapes.medium)
        .clickable(role = Role.Button, onClick = toggle).padding(HEADER_INSET), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.editor_options), style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.semantics { heading() })
            if (!expanded) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectField(label: String, value: String, values: List<String>, enabled: Boolean, isError: Boolean, note: String? = null,
    hints: Map<String, String> = emptyMap(), choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(choiceText(value), {}, readOnly = true, enabled = enabled, singleLine = true, isError = isError,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            supportingText = note?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled))
        // Items are inset 4dp on every side, so the menu's corners are the items' 12dp (medium) plus 4dp.
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.trimMenuPadding(), shape = MaterialTheme.shapes.large) {
            values.forEachIndexed { index, choice ->
                // The empty leading slot keeps every label aligned instead of shifting the selected one by the check's width.
                // That makes items 48dp tall, so the touch-target inset no longer separates them: add the 2dp the library
                // only gives items with supporting text. The library already insets items 4dp at the sides.
                SelectableDropdownMenuItem(selected = choice == value, onClick = { choose(choice); expanded = false },
                    modifier = if (choice in hints) Modifier else Modifier.padding(vertical = ITEM_SPACING),
                    text = { Text(choiceText(choice)) }, shapes = MenuDefaults.itemShape(index, values.size),
                    supportingText = hints[choice]?.let { { Text(it) } },
                    leadingIcon = { Spacer(Modifier.size(24.dp)) },
                    selectedLeadingIcon = { Icon(AppIcons.Check, contentDescription = null) },
                    colors = MenuDefaults.selectableItemColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer))
            }
        }
    }
}

/** Lays the content out [inset] larger on every side than the space it takes up, without moving its neighbors. */
private fun Modifier.bleed(inset: Dp) = layout { measurable, constraints ->
    val bleed = inset.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * bleed, vertical = 2 * bleed))
    layout((placeable.width - 2 * bleed).coerceAtLeast(0), (placeable.height - 2 * bleed).coerceAtLeast(0)) {
        placeable.place(-bleed, -bleed)
    }
}

/**
 * The menu pads its list by a fixed 8dp top and bottom (internal DropdownMenuVerticalPadding). Trim it so that, with each
 * item's own [ITEM_SPACING], the list sits [MENU_INSET] from the top and bottom edges like it does from the sides.
 */
private fun Modifier.trimMenuPadding() = layout { measurable, constraints ->
    val trim = (8.dp - (MENU_INSET - ITEM_SPACING)).roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = 2 * trim))
    layout(placeable.width, (placeable.height - 2 * trim).coerceAtLeast(0)) { placeable.place(0, -trim) }
}

@Composable
private fun formatHints() = mapOf(
    "AUTO" to stringResource(R.string.hint_format_auto),
    "TSV" to stringResource(R.string.hint_format_tsv),
    "MS_WORDLIST" to stringResource(R.string.hint_format_ms),
    "PLIST_XML" to stringResource(R.string.hint_format_plist),
    "APPLE_CSV" to stringResource(R.string.hint_format_csv),
)

@Composable
internal fun choiceText(value: String): String = when (value) {
    "AUTO" -> stringResource(R.string.choice_auto)
    "TSV" -> stringResource(R.string.choice_tsv)
    "MS_WORDLIST" -> stringResource(R.string.choice_ms)
    "PLIST_XML" -> stringResource(R.string.choice_plist)
    "APPLE_CSV" -> stringResource(R.string.choice_csv)
    "ja" -> stringResource(R.string.choice_ja)
    "en" -> stringResource(R.string.choice_en)
    "ALL" -> stringResource(R.string.choice_all)
    "CUSTOM" -> stringResource(R.string.choice_custom)
    "windows-31j" -> stringResource(R.string.choice_cp932)
    else -> value
}
