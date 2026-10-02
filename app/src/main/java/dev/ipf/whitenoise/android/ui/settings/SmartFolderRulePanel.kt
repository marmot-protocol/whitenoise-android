@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Shared by the live editing session and its full-form render tests. Every write is owned by the caller. */
@Composable
@Suppress("LongParameterList", "LongMethod") // Stateless property controls remain one visual section.
internal fun SmartFolderRulePanel(
    state: SmartFolderPanelState,
    people: List<WhiteNoisePickerItem>,
    resolveKey: suspend (String) -> String?,
    onStart: () -> Unit,
    onChange: (SmartFolderFilter.Group) -> Unit,
    legacyControls: @Composable () -> Unit,
) {
    var legacyExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingStart by rememberSaveable { mutableStateOf<FolderRuleStart?>(null) }
    val applyPreset: (Boolean) -> Unit = { allRead ->
        val condition =
            if (allRead) {
                SmartFolderFilter.Condition(FolderField.UNREAD, FolderMode.NONE)
            } else {
                SmartFolderFilter.Condition(FolderField.MENTIONS)
            }
        val preset = defaultSmartFolder()
        onChange(preset.copy(children = preset.children + condition))
    }
    val startRules: (FolderRuleStart) -> Unit = { choice ->
        if (choice == FolderRuleStart.CUSTOM) {
            onStart()
        } else {
            applyPreset(choice == FolderRuleStart.ALL_READ)
        }
    }
    val requestStart: (FolderRuleStart) -> Unit = { choice ->
        if (state.advanced || state.confirmSimpleReplacement) {
            pendingStart = choice
        } else {
            startRules(choice)
        }
    }
    SettingsSection(stringResource(R.string.folder_rules))
    Column(
        Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.advanced) {
            Text(stringResource(R.string.smart_folder_legacy), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.smart_folder_preset_hint), style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = { requestStart(FolderRuleStart.ALL_READ) },
                modifier = Modifier.testTag("folder.presetRead"),
            ) {
                Text(stringResource(R.string.smart_folder_preset_read))
            }
            TextButton(
                onClick = { requestStart(FolderRuleStart.MENTIONS) },
                modifier = Modifier.testTag("folder.presetMentions"),
            ) {
                Text(stringResource(R.string.smart_folder_preset_mentions))
            }
            TextButton(
                onClick = { requestStart(FolderRuleStart.CUSTOM) },
                modifier = Modifier.testTag("folder.upgrade"),
            ) {
                Text(stringResource(R.string.smart_folder_start))
            }
            TextButton(
                onClick = {
                    legacyExpanded = !legacyExpanded
                },
                modifier = Modifier.testTag("folder.legacyEdit"),
            ) {
                Text(stringResource(R.string.smart_folder_edit_legacy))
            }
            if (legacyExpanded) legacyControls()
        } else {
            Text(stringResource(R.string.smart_folder_prototype), style = MaterialTheme.typography.bodySmall)
            if (state.unresolved > 0) {
                Text(
                    stringResource(
                        R.string.smart_folder_unresolved,
                        state.unresolved,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val root = state.root
            if (root == null) {
                Text(stringResource(R.string.smart_folder_invalid), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { requestStart(FolderRuleStart.CUSTOM) }) {
                    Text(stringResource(R.string.smart_folder_start))
                }
            } else {
                FolderChoice(
                    stringResource(R.string.smart_folder_presets),
                    listOf(
                        true to stringResource(R.string.smart_folder_preset_read),
                        false to stringResource(R.string.smart_folder_preset_mentions),
                    ),
                    "folder.presets",
                ) { requestStart(if (it) FolderRuleStart.ALL_READ else FolderRuleStart.MENTIONS) }
                SmartFolderEditor(root, people, resolveKey, onChange)
            }
        }
    }
    pendingStart?.let { choice ->
        WhiteNoiseAlertDialog(
            onDismissRequest = { pendingStart = null },
            title = {
                Text(
                    stringResource(
                        when (choice) {
                            FolderRuleStart.ALL_READ -> R.string.smart_folder_preset_read
                            FolderRuleStart.MENTIONS -> R.string.smart_folder_preset_mentions
                            FolderRuleStart.CUSTOM -> R.string.smart_folder_start
                        },
                    ),
                )
            },
            text = { Text(stringResource(R.string.smart_folder_replace_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    startRules(choice)
                    pendingStart = null
                }, modifier = Modifier.testTag("folder.confirmPreset")) {
                    Text(stringResource(R.string.smart_folder_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingStart = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private enum class FolderRuleStart { ALL_READ, MENTIONS, CUSTOM }
