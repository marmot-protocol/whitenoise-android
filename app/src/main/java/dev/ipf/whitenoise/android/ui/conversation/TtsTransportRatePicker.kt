package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsRatePreferences
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.settings.TtsCustomRateEditor
import dev.ipf.whitenoise.android.ui.settings.isTtsCustomRate
import dev.ipf.whitenoise.android.ui.settings.ttsRateLabel

/**
 * Picks a playback rate and exposes its current value on the shared speed button. The menu is the shared value
 * menu anchored to that button: every rate is a radio choice and the current one carries a check, so the speed in
 * use is visible both on the button and inside the menu. Custom swaps the rows for an inline editor.
 */
@Suppress("FunctionNaming")
@Composable
internal fun TtsTransportRatePicker(
    rateOverride: Float?,
    activeRate: Float,
    onRateSelected: (Float?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var customEditorOpen by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val rateLabel = ttsRateLabel(activeRate, locale)
    val controlDescription = stringResource(R.string.tts_bar_rate_control, rateLabel)
    val select: (Float?) -> Unit = { rate ->
        customEditorOpen = false
        menuOpen = false
        onRateSelected(rate)
    }

    Box {
        PlaybackSpeedChip(
            label = rateLabel,
            onClick = { menuOpen = true },
            modifier =
                Modifier.semantics {
                    contentDescription = controlDescription
                    stateDescription = rateLabel
                },
        )
        WhiteNoiseDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                customEditorOpen = false
            },
            header =
                if (customEditorOpen) {
                    {
                        TtsCustomRateEditor(
                            initialRate = activeRate,
                            onDismiss = { customEditorOpen = false },
                            onRateSelected = select,
                        )
                    }
                } else {
                    null
                },
            items =
                if (customEditorOpen) {
                    emptyList()
                } else {
                    transportRateItems(rateOverride, locale, select) { customEditorOpen = true }
                },
        )
    }
}

/** The system rate, every preset, then Custom, with exactly the stored override marked as the current choice. */
@Composable
private fun transportRateItems(
    rateOverride: Float?,
    locale: java.util.Locale,
    onRateSelected: (Float?) -> Unit,
    onOpenCustom: () -> Unit,
): List<WhiteNoiseMenuItem> =
    buildList {
        add(
            WhiteNoiseMenuItem(
                label = stringResource(R.string.tts_settings_rate_system),
                selected = rateOverride == null,
                onClick = { onRateSelected(null) },
            ),
        )
        TtsRatePreferences.PRESET_RATES.forEach { rate ->
            add(
                WhiteNoiseMenuItem(
                    label = ttsRateLabel(rate, locale),
                    selected = rateOverride == rate,
                    onClick = { onRateSelected(rate) },
                ),
            )
        }
        add(
            WhiteNoiseMenuItem(
                label = stringResource(R.string.tts_rate_custom),
                selected = isTtsCustomRate(rateOverride),
                closesMenu = false,
                onClick = onOpenCustom,
            ),
        )
    }
