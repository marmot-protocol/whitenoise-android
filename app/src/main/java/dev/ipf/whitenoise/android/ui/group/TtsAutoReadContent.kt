package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext

internal const val TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG = "tts_auto_read_global_default_row"
internal const val TTS_AUTO_READ_GROUP_ROW_TAG = "tts_auto_read_group_row"

/**
 * The per-chat Read Aloud row inside a chat's actions group: the title with the resolved provenance as its value,
 * announced together, opening the override picker.
 */
@Suppress("FunctionNaming")
@Composable
internal fun TtsAutoReadGroupActionRow(
    context: SettingsRowContext,
    title: String,
    provenanceLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsLink(
        context = context,
        title = title,
        onClick = onClick,
        modifier =
            modifier
                .testTag(TTS_AUTO_READ_GROUP_ROW_TAG)
                .semantics(mergeDescendants = true) { contentDescription = "$title. $provenanceLabel" },
        value = provenanceLabel,
        leading = { Icon(painterResource(R.drawable.ic_volume_up), contentDescription = null) },
    )
}

/** The three override choices: follow the global default (named by its current value), always on, always off. */
@Suppress("FunctionNaming")
@Composable
internal fun TtsAutoReadPickerContent(
    globalDefaultEnabled: Boolean,
    selectedOverride: TtsAutoReadOverride?,
    onSelect: (TtsAutoReadOverride?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup()) {
        WhiteNoiseDialogChoiceRow(
            title = stringResource(ttsAutoReadSettingLabelRes(null, globalDefaultEnabled)),
            selected = selectedOverride == null,
            onClick = { onSelect(null) },
        )
        WhiteNoiseDialogChoiceRow(
            title = stringResource(R.string.tts_auto_read_override_on),
            selected = selectedOverride == TtsAutoReadOverride.ON,
            onClick = { onSelect(TtsAutoReadOverride.ON) },
        )
        WhiteNoiseDialogChoiceRow(
            title = stringResource(R.string.tts_auto_read_override_off),
            selected = selectedOverride == TtsAutoReadOverride.OFF,
            onClick = { onSelect(TtsAutoReadOverride.OFF) },
        )
    }
}
