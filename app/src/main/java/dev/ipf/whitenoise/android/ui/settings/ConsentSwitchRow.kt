@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults

/**
 * A consent choice on the diagnostics card, drawn as the shared [SettingsSwitch] so its height, insets and type
 * match every other settings switch. The card behind it owns the fill and outline, so the row is transparent and
 * unbordered. The whole row toggles. While [saving], the pending choice keeps its selected thumb and colours and the
 * row ignores taps and reads as disabled, so the person sees what they picked while it is stored.
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
        // Not `busy`: that swaps the switch for a progress indicator and dims the row, hiding the pending choice.
        onCheckedChange = { if (!saving) onCheckedChange(it) },
        modifier = if (saving) Modifier.semantics { disabled() } else Modifier,
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
