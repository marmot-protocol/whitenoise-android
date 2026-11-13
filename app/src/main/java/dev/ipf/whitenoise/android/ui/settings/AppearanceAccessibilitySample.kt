package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private const val EXPANDED_DISCLOSURE_ROTATION = 180f

/** Synthetic sample; the screen retains simulation state without writing app or system preferences. */
@Composable
@Suppress("FunctionNaming")
internal fun AppearanceAccessibilitySample(
    state: AppearancePreviewState,
    theme: BubbleTheme,
    mineArgb: Long?,
    otherArgb: Long?,
) {
    Column(Modifier.fillMaxWidth().testTag("appearance.accessibility.preview")) {
        SettingsGroup {
            row("preview_summary") { context ->
                AppearancePreviewDisclosure(context, state)
            }
            if (state.expanded) {
                row("preview_rtl") { context ->
                    SettingsSwitch(
                        context = context,
                        title = stringResource(R.string.appearance_preview_rtl),
                        checked = state.rtl,
                        onCheckedChange = { state.rtl = it },
                        leading = { SettingsLeadingIcon(R.drawable.ic_format_textdirection_r_to_l) },
                    )
                }
                row("preview_large") { context ->
                    SettingsSwitch(
                        context = context,
                        title = stringResource(R.string.appearance_preview_large_text),
                        checked = state.largeText,
                        onCheckedChange = { state.largeText = it },
                        leading = { SettingsLeadingIcon(R.drawable.ic_text_increase) },
                    )
                }
                row("preview_sample") { context ->
                    AppearancePreviewContent(context, state, theme, mineArgb, otherArgb)
                }
            }
        }
    }
}

/** The preview is optional; its explanation and disclosure share the same compact group row. */
@Suppress("FunctionNaming")
@Composable
private fun AppearancePreviewDisclosure(
    context: SettingsRowContext,
    state: AppearancePreviewState,
) {
    val disclosure = stringResource(if (state.expanded) R.string.hide else R.string.show)
    SettingsLink(
        context = context,
        title = stringResource(R.string.appearance_accessibility_preview),
        subtitle = stringResource(R.string.appearance_preview_intro),
        onClick = { state.expanded = !state.expanded },
        modifier = Modifier.semantics { stateDescription = disclosure },
        trailing = {
            Icon(
                painterResource(R.drawable.ic_expand_more),
                null,
                Modifier.rotate(if (state.expanded) EXPANDED_DISCLOSURE_ROTATION else 0f),
            )
        },
        leading = { SettingsLeadingIcon(R.drawable.ic_visibility) },
    )
}

/** Existing sample behavior remains isolated from app preferences inside its group panel. */
@Suppress("FunctionNaming")
@Composable
private fun AppearancePreviewContent(
    context: SettingsRowContext,
    state: AppearancePreviewState,
    theme: BubbleTheme,
    mineArgb: Long?,
    otherArgb: Long?,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val motion = rememberSystemAnimationsEnabled()
    SettingsGroupPanel(context) {
        Column(
            Modifier.padding(WhiteNoiseSpacing.CompactScreenMargin),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.appearance_preview_limits), style = MaterialTheme.typography.bodySmall)
            CompositionLocalProvider(
                LocalLayoutDirection provides if (state.rtl) LayoutDirection.Rtl else direction,
                LocalDensity provides Density(density.density, density.fontScale * if (state.largeText) 2f else 1f),
            ) {
                FontAppearanceSample(theme, mineArgb, otherArgb, Modifier.testTag("appearance.accessibility.sample"))
            }
            val motionLabel =
                if (motion) {
                    R.string.appearance_preview_motion_on
                } else {
                    R.string.appearance_preview_motion_off
                }
            Text(
                stringResource(motionLabel),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = state::reset) {
                Text(stringResource(R.string.appearance_preview_reset))
            }
        }
    }
}
