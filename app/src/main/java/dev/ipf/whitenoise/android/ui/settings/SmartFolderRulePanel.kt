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
    SettingsSection(stringResource(R.string.folder_rules))
    Column(
        Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.advanced) {
            Text(stringResource(R.string.smart_folder_legacy), style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = {
                    legacyExpanded = !legacyExpanded
                },
                modifier = Modifier.testTag("folder.legacyEdit"),
            ) {
                Text(stringResource(R.string.smart_folder_edit_legacy))
            }
            if (legacyExpanded) legacyControls()
            TextButton(
                onClick = onStart,
                modifier = Modifier.testTag("folder.upgrade"),
            ) {
                Text(stringResource(R.string.smart_folder_start))
            }
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
                TextButton(onClick = onStart) { Text(stringResource(R.string.smart_folder_start)) }
            } else {
                SmartFolderEditor(root, people, resolveKey, onChange)
                Text(
                    stringResource(R.string.smart_folder_presets),
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(
                    onClick = {
                        onChange(
                            defaultSmartFolder().let {
                                it.copy(
                                    children =
                                        it.children +
                                            SmartFolderFilter.Condition(
                                                FolderField.UNREAD,
                                                FolderMode.NONE,
                                            ),
                                )
                            },
                        )
                    },
                    modifier = Modifier.testTag("folder.presetRead"),
                ) {
                    Text(stringResource(R.string.smart_folder_preset_read))
                }
                TextButton(
                    onClick = {
                        onChange(
                            defaultSmartFolder().let {
                                it.copy(children = it.children + SmartFolderFilter.Condition(FolderField.MENTIONS))
                            },
                        )
                    },
                    modifier = Modifier.testTag("folder.presetMentions"),
                ) {
                    Text(stringResource(R.string.smart_folder_preset_mentions))
                }
                Text(
                    stringResource(R.string.smart_folder_preset_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
