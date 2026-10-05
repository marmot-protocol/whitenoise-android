@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.common.scrollEdgeFade
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Shared by the live editing session and render tests; replacement is always an explicit draft edit. */
@Composable
@Suppress("LongMethod") // One bounded section with a shared replacement confirmation.
internal fun SmartFolderRulePanel(
    state: SmartFolderPanelState,
    people: List<WhiteNoisePickerItem>,
    resolveKey: suspend (String) -> String?,
    onChange: (SmartFolderFilter.Group) -> Unit,
    legacyControls: @Composable () -> Unit,
) {
    val editSimpleRules = rememberSaveable { state.confirmSimpleReplacement }
    var legacyExpanded by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var pendingPayload by rememberSaveable { mutableStateOf<String?>(null) }
    val requestRules: (SmartFolderFilter.Group) -> Unit = { tree ->
        if (state.advanced || state.confirmSimpleReplacement) {
            pendingPayload = SmartFolderCodec.encode(tree)
        } else {
            onChange(tree)
        }
    }
    val applyPreset: (Boolean) -> Unit = { allRead ->
        val condition =
            if (allRead) {
                SmartFolderFilter.Condition(FolderField.UNREAD, FolderMode.NONE)
            } else {
                SmartFolderFilter.Condition(FolderField.MENTIONS)
            }
        val preset = defaultSmartFolder()
        requestRules(preset.copy(children = preset.children + condition))
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.smart_folder_filters),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.advanced || editSimpleRules) {
                SmartFolderPresetMenu(applyPreset)
            }
        }
        if (!state.advanced) {
            if (!editSimpleRules) {
                TextButton(onClick = { applyPreset(true) }, modifier = Modifier.testTag("folder.presetRead")) {
                    Text(stringResource(R.string.smart_folder_preset_read))
                }
                TextButton(onClick = { applyPreset(false) }, modifier = Modifier.testTag("folder.presetMentions")) {
                    Text(stringResource(R.string.smart_folder_preset_mentions))
                }
            }
            TextButton(onClick = { adding = true }, modifier = Modifier.testTag("folder.add.")) {
                Text(stringResource(R.string.smart_folder_add))
            }
            if (editSimpleRules) {
                TextButton(
                    onClick = { legacyExpanded = !legacyExpanded },
                    modifier = Modifier.testTag("folder.legacyEdit"),
                ) {
                    val label = stringResource(R.string.smart_folder_edit_legacy)
                    FolderButtonLabel(label, R.drawable.ic_expand_more, legacyExpanded)
                }
                if (legacyExpanded) legacyControls()
            }
        } else {
            val root = state.root
            if (root == null) {
                Text(stringResource(R.string.smart_folder_invalid), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { requestRules(defaultSmartFolder()) }) {
                    Text(stringResource(R.string.smart_folder_start))
                }
            } else {
                SmartFolderEditor(root, people, resolveKey, onChange)
            }
        }
    }
    if (adding) {
        SmartFolderAddFilter(
            people,
            resolveKey,
            onDismiss = { adding = false },
            onDone = { condition ->
                adding = false
                val defaults = defaultSmartFolder()
                requestRules(
                    defaults.copy(
                        children =
                            defaults.children.filterNot {
                                (it as? SmartFolderFilter.Condition)?.field == condition.field
                            } + condition,
                    ),
                )
            },
        )
    }
    pendingPayload?.let { payload ->
        WhiteNoiseAlertDialog(
            onDismissRequest = { pendingPayload = null },
            title = { Text(stringResource(R.string.smart_folder_replace)) },
            text = { Text(stringResource(R.string.smart_folder_replace_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SmartFolderCodec.decode(payload)?.let(onChange)
                        pendingPayload = null
                    },
                    modifier = Modifier.testTag("folder.confirmPreset"),
                ) { Text(stringResource(R.string.smart_folder_replace)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPayload = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SmartFolderPresetMenu(onSelect: (Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("folder.presets")) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.smart_folder_presets))
        }
        val menuScrollState = rememberScrollState()
        DropdownMenu(
            scrollState = menuScrollState,
            modifier = Modifier.scrollEdgeFade(menuScrollState),
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            listOf(true, false).forEach { allRead ->
                val title = if (allRead) R.string.smart_folder_preset_read else R.string.smart_folder_preset_mentions
                DropdownMenuItem(
                    text = { Text(stringResource(title)) },
                    onClick = {
                        open = false
                        onSelect(allRead)
                    },
                )
            }
        }
    }
}
