package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerRow
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val SMART_FOLDER_PEOPLE_PAGE_TAG = "folder.peoplePage"

/**
 * The People filter as a page with Back (docs/ui-patterns.md, bottom sheets): choosing people used to go sheet →
 * dialog → people-picker sheet, so the mode, options and the searchable people list now share one page. Save applies
 * the condition. Back leaves the folder draft untouched, and asks before discarding a changed condition.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // One staged form with its people list.
internal fun SmartFolderPeopleConditionPage(
    initial: SmartFolderFilter.Condition,
    people: List<WhiteNoisePickerItem>,
    resolveKey: suspend (String) -> String?,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)?,
    onDone: (SmartFolderFilter.Condition) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var not by rememberSaveable { mutableStateOf(initial.not) }
    var optionsExpanded by rememberSaveable { mutableStateOf(initial.not) }
    var keys by rememberSaveable { mutableStateOf(initial.values.toList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var invalidKey by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    val publicKey = rememberTextFieldState()
    val scope = rememberCoroutineScope()
    val condition = SmartFolderFilter.Condition(FolderField.PARTICIPANTS, mode, keys.toSet(), not)
    val valid = SmartFolderCodec.valid(SmartFolderFilter.Group(children = listOf(condition)))
    val changed = condition != initial
    val rows = peopleConditionRows(people, keys)
    val needle = query.trim()
    val visible = if (needle.isEmpty()) rows else rows.filter { it.title.contains(needle, ignoreCase = true) }
    val title = stringResource(fieldLabel(FolderField.PARTICIPANTS))
    MeasuredPlaybackDialog(
        origin = null,
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        // Playback-source navigation would leave the page without the discard prompt, so it waits for no changes.
        onSourceDismiss = onDismiss.takeIf { !changed },
        dismissEnabled = { !changed },
    ) {
        val surface = this
        // A changed condition is not dropped by a stray Back, an unchanged one just closes.
        val back = { if (changed) confirmDiscard = true else surface.dismiss() }
        BackHandler(enabled = changed, onBack = back)
        if (confirmDiscard) {
            ChatFolderDiscardDialog(
                onDiscard = {
                    confirmDiscard = false
                    surface.close(onDismiss)
                },
                onKeepEditing = { confirmDiscard = false },
            )
        }
        WhiteNoiseScaffold(
            modifier = Modifier.fillMaxSize().testTag(SMART_FOLDER_PEOPLE_PAGE_TAG),
            topBar = {
                WhiteNoiseTopBar(title = title, onBack = back) {
                    TextButton(
                        enabled = valid && !resolving,
                        onClick = { surface.close { onDone(condition) } },
                        modifier = Modifier.testTag("folder.conditionDone"),
                    ) { Text(stringResource(R.string.save)) }
                }
            },
        ) { padding ->
            WhiteNoiseLazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = WhiteNoiseSpacing.Related),
            ) {
                item(key = "controls") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)) {
                        val modeLabel = stringResource(conditionModeLabel(FolderField.PARTICIPANTS, mode))
                        FolderChoice(
                            modeLabel,
                            PEOPLE_MODES.map { it to stringResource(conditionModeLabel(FolderField.PARTICIPANTS, it)) },
                            "folder.mode",
                            Explanation(modeLabel, stringResource(R.string.explanation_organize_cond_mode)),
                        ) { mode = it }
                        TextButton(
                            onClick = { optionsExpanded = !optionsExpanded },
                            modifier =
                                Modifier
                                    .testTag("folder.conditionOptions")
                                    .explainOnLongPress(
                                        Explanation(
                                            stringResource(R.string.more_options),
                                            stringResource(R.string.explanation_organize_cond_options),
                                        ),
                                    ),
                        ) {
                            FolderButtonLabel(
                                stringResource(R.string.more_options),
                                R.drawable.ic_expand_more,
                                optionsExpanded,
                            )
                        }
                        if (optionsExpanded) {
                            val negateLabel =
                                stringResource(
                                    if (not) {
                                        R.string.smart_folder_negate_condition
                                    } else {
                                        R.string.smart_folder_include_match
                                    },
                                )
                            FolderChoice(
                                negateLabel,
                                listOf(
                                    false to stringResource(R.string.smart_folder_include_match),
                                    true to stringResource(R.string.smart_folder_negate_condition),
                                ),
                                "folder.conditionNot",
                                Explanation(negateLabel, stringResource(R.string.explanation_organize_cond_not)),
                            ) { not = it }
                            WhiteNoiseTextField(
                                state = publicKey,
                                label = { Text(stringResource(R.string.smart_folder_key)) },
                                lineLimits = TextFieldLineLimits.SingleLine,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                enabled = !resolving && keys.size < SmartFolderCodec.MAX_VALUES,
                                modifier =
                                    Modifier.explainOnLongPress(
                                        Explanation(
                                            stringResource(R.string.smart_folder_add_key),
                                            stringResource(R.string.explanation_organize_cond_add_key),
                                        ),
                                    ),
                                onClick = {
                                    resolving = true
                                    val input = publicKey.text.toString()
                                    scope.launch {
                                        val hex =
                                            withContext(Dispatchers.IO) { resolveKey(input) }
                                                ?.lowercase(java.util.Locale.ROOT)
                                        invalidKey = !isPubkeyHex(hex)
                                        // People picked while the key resolved may have reached the limit meanwhile.
                                        val fits = hex in keys || keys.size < SmartFolderCodec.MAX_VALUES
                                        val accepted = !invalidKey && hex != null && fits
                                        if (accepted && publicKey.text.toString() == input) {
                                            keys = (keys + hex).distinct()
                                        }
                                        resolving = false
                                    }
                                },
                            ) { Text(stringResource(R.string.smart_folder_add_key)) }
                            if (invalidKey) {
                                WhiteNoiseCallout(
                                    text = stringResource(R.string.smart_folder_invalid_key),
                                    icon = R.drawable.ic_error,
                                    isError = true,
                                )
                            }
                        }
                        onRemove?.let { remove ->
                            TextButton(
                                onClick = { surface.close(remove) },
                                modifier =
                                    Modifier.explainOnLongPress(
                                        Explanation(
                                            stringResource(R.string.smart_folder_ignore),
                                            stringResource(R.string.explanation_organize_cond_remove),
                                        ),
                                    ),
                            ) { Text(stringResource(R.string.smart_folder_ignore)) }
                        }
                        Text(
                            stringResource(R.string.smart_folder_select_people) + " (${keys.size})",
                            style = MaterialTheme.typography.titleSmall,
                            modifier =
                                Modifier
                                    .padding(top = WhiteNoiseSpacing.Related)
                                    .testTag("folder.choosePeople")
                                    .explainOnLongPress(
                                        Explanation(
                                            stringResource(R.string.smart_folder_select_people),
                                            stringResource(R.string.explanation_organize_cond_choose_people),
                                        ),
                                    ),
                        )
                        WhiteNoiseCompactSearchField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = stringResource(R.string.folder_search),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = WhiteNoiseSpacing.Related)
                                    .testTag("entity.search"),
                        )
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "empty") { WhiteNoiseEmptyState(stringResource(R.string.no_matches)) }
                }
                itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                    WhiteNoisePickerRow(
                        item = item,
                        index = index,
                        count = visible.size,
                        checked = item.id in keys,
                        multiple = true,
                        onSelect = { id ->
                            keys = if (id in keys) keys - id else (keys + id).take(SmartFolderCodec.MAX_VALUES)
                        },
                        modifier = Modifier.testTag("entity.choice.${item.id}"),
                    )
                }
            }
        }
    }
}

/** Known people first, then every chosen key that is not one of them, shown by a short prefix. */
internal fun peopleConditionRows(
    people: List<WhiteNoisePickerItem>,
    keys: List<String>,
): List<WhiteNoisePickerItem> =
    people +
        keys
            .filter { key -> people.none { it.id == key } }
            .map { WhiteNoisePickerItem(id = it, title = it.take(KEY_ROW_PREFIX_LENGTH) + "…", avatarSeed = it) }

/** Whether a resolved key is a full lower-case hex public key the codec accepts. */
internal fun isPubkeyHex(hex: String?): Boolean =
    hex != null && hex.length == SmartFolderCodec.PUBKEY_HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }

private val PEOPLE_MODES = listOf(FolderMode.ANY_OF, FolderMode.ALL_OF, FolderMode.EXCLUDES)
private const val KEY_ROW_PREFIX_LENGTH = 16
