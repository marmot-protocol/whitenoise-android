@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults

/**
 * A consent choice on the diagnostics card, drawn as the shared [SettingsSwitch] so its height, insets and type
 * match every other settings switch. The card behind it owns the fill and outline, so the row is transparent and
 * unbordered. The whole row toggles; while [saving] the row is busy, which blocks input and shows the progress
 * indicator in the switch's place, exactly as other settings switches report a pending save.
 */
@Composable
internal fun ConsentSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    saving: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    explanation: String? = null,
) {
    SettingsSwitch(
        context = consentRowContext(),
        title = title,
        subtitle = subtitle,
        checked = checked,
        enabled = enabled,
        busy = saving,
        onCheckedChange = onCheckedChange,
        explanation = explanation,
        leading = null,
    )
}

/** One unbordered, transparent row: the enclosing card already draws the shape, fill and AMOLED outline. */
@Composable
private fun consentRowContext(): SettingsRowContext =
    SettingsRowContext(
        shapes = WhiteNoiseListItemDefaults.segmentedShapes(0, 1),
        containerColor = Color.Transparent,
        borderColor = Color.Unspecified,
        suppressChrome = { true },
    )
