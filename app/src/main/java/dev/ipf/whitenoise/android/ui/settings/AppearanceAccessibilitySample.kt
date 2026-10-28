package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled

/** Synthetic, account-lifetime sample; simulation state never writes to app or system preferences. */
@Composable
@Suppress("FunctionNaming")
internal fun AppearanceAccessibilitySample(
    owner: Any,
    theme: BubbleTheme,
    mineArgb: Long?,
    otherArgb: Long?,
) {
    var rtl by remember(owner) { mutableStateOf(false) }
    var largeText by remember(owner) { mutableStateOf(false) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val motion = rememberSystemAnimationsEnabled()
    Column(
        Modifier.fillMaxWidth().padding(16.dp).testTag("appearance.accessibility.preview"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.appearance_accessibility_preview), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.appearance_preview_limits), style = MaterialTheme.typography.bodySmall)
        SettingsGroup {
            row("preview_rtl") { context ->
                SettingsSwitch(
                    context = context,
                    title = stringResource(R.string.appearance_preview_rtl),
                    checked = rtl,
                    onCheckedChange = { rtl = it },
                )
            }
            row("preview_large") { context ->
                SettingsSwitch(
                    context = context,
                    title = stringResource(R.string.appearance_preview_large_text),
                    checked = largeText,
                    onCheckedChange = { largeText = it },
                )
            }
        }
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else direction,
            LocalDensity provides Density(density.density, density.fontScale * if (largeText) 2f else 1f),
        ) {
            FontAppearanceSample(theme, mineArgb, otherArgb, Modifier.testTag("appearance.accessibility.sample"))
        }
        Text(
            stringResource(
                if (motion) R.string.appearance_preview_motion_on else R.string.appearance_preview_motion_off,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = {
                rtl = false
                largeText = false
            },
        ) {
            Text(stringResource(R.string.appearance_preview_reset))
        }
    }
}
