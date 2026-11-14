package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.ui.common.PopupSourceActivation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDialogChoiceRow
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext

internal const val TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG = "tts_auto_read_global_default_row"
internal const val TTS_AUTO_READ_GROUP_ROW_TAG = "tts_auto_read_group_row"

/**
 * The per-chat Read Aloud row inside a chat's actions group: the title with the resolved provenance as its value,
 * announced together, opening the override picker. The row carries its own speech glyph, so every caller
 * gets the icon that belongs to it rather than restating it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun TtsAutoReadGroupActionRow(
    context: SettingsRowContext,
    title: String,
    provenanceLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sourceOwner: Any? = null,
    sourceActivation: PopupSourceActivation? = null,
    onSourceActivated: ((PopupSourceActivation?) -> Unit)? = null,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var coordinates by remember(sourceOwner) { mutableStateOf<LayoutCoordinates?>(null) }
    val sourceOwnsContour = { sourceActivation?.ownerKey == sourceOwner && sourceActivation?.ownsContour == true }
    SettingsLink(
        context =
            SettingsRowContext(
                context.shapes,
                context.containerColor,
                context.borderColor,
                suppressChrome = { context.suppressChrome() || sourceOwnsContour() },
                suppressSeam = { context.suppressSeam() || sourceOwnsContour() },
            ),
        title = title,
        onClick = {
            if (sourceOwner != null) {
                onSourceActivated?.invoke(
                    PopupSourceActivation.capture(
                        sourceOwner,
                        coordinates,
                        view,
                        context.shapes.shape,
                        context.containerColor,
                        if (context.borderColor.isSpecified) BorderStroke(1.dp, context.borderColor) else null,
                        density,
                        direction,
                    ),
                )
            }
            onClick()
        },
        modifier =
            modifier
                .onGloballyPositioned { coordinates = it }
                .testTag(TTS_AUTO_READ_GROUP_ROW_TAG)
                .semantics(mergeDescendants = true) { contentDescription = "$title. $provenanceLabel" },
        value = provenanceLabel,
        leading = { SettingsLeadingIcon(R.drawable.ic_volume_up) },
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
    enabled: Boolean = true,
) {
    Column(modifier.selectableGroup()) {
        WhiteNoiseDialogChoiceRow(
            title = stringResource(ttsAutoReadSettingLabelRes(null, globalDefaultEnabled)),
            enabled = enabled,
            selected = selectedOverride == null,
            onClick = { onSelect(null) },
        )
        WhiteNoiseDialogChoiceRow(
            title = stringResource(R.string.tts_auto_read_override_on),
            enabled = enabled,
            selected = selectedOverride == TtsAutoReadOverride.ON,
            onClick = { onSelect(TtsAutoReadOverride.ON) },
        )
        WhiteNoiseDialogChoiceRow(
            title = stringResource(R.string.tts_auto_read_override_off),
            enabled = enabled,
            selected = selectedOverride == TtsAutoReadOverride.OFF,
            onClick = { onSelect(TtsAutoReadOverride.OFF) },
        )
    }
}
