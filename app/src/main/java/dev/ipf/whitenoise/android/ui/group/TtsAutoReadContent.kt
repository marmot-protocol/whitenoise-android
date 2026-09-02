package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.ui.settings.SelectableSettingsRow
import dev.ipf.whitenoise.android.ui.settings.settingsRowAmoledSurfaceBorder
import dev.ipf.whitenoise.android.ui.theme.Dimens

internal const val TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG = "tts_auto_read_global_default_row"
internal const val TTS_AUTO_READ_GROUP_ROW_TAG = "tts_auto_read_group_row"
internal const val TTS_AUTO_READ_GROUP_TITLE_TAG = "tts_auto_read_group_title"
internal const val TTS_AUTO_READ_GROUP_PROVENANCE_TAG = "tts_auto_read_group_provenance"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming")
@Composable
internal fun TtsAutoReadGlobalDefaultRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.tts_auto_read_default_global_title)
    val subtitle = stringResource(R.string.tts_auto_read_default_global_subtitle)
    Row(
        modifier
            .fillMaxWidth()
            .settingsRowAmoledSurfaceBorder()
            .testTag(TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ).semantics(mergeDescendants = true) {
                contentDescription = "$title. $subtitle"
            }.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun TtsAutoReadGroupActionRow(
    title: String,
    provenanceLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.AutoMirrored.Filled.VolumeUp,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(TTS_AUTO_READ_GROUP_ROW_TAG)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$title. $provenanceLabel"
                }.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(TTS_AUTO_READ_GROUP_TITLE_TAG),
            )
            Text(
                provenanceLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TTS_AUTO_READ_GROUP_PROVENANCE_TAG),
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun TtsAutoReadPickerContent(
    globalDefaultEnabled: Boolean,
    selectedOverride: TtsAutoReadOverride?,
    onSelect: (TtsAutoReadOverride?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup()) {
        SelectableSettingsRow(
            title = stringResource(ttsAutoReadSettingLabelRes(null, globalDefaultEnabled)),
            selected = selectedOverride == null,
            onClick = { onSelect(null) },
        )
        SelectableSettingsRow(
            title = stringResource(R.string.tts_auto_read_override_on),
            selected = selectedOverride == TtsAutoReadOverride.ON,
            onClick = { onSelect(TtsAutoReadOverride.ON) },
        )
        SelectableSettingsRow(
            title = stringResource(R.string.tts_auto_read_override_off),
            selected = selectedOverride == TtsAutoReadOverride.OFF,
            onClick = { onSelect(TtsAutoReadOverride.OFF) },
        )
    }
}
