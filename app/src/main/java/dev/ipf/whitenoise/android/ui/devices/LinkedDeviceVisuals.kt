@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.LinkedDeviceRow
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.util.Locale

internal const val DEVICE_DETAIL_HERO_TAG = "device_detail.hero"

private val HeaderGlyphSize = 48.dp

/**
 * The glyph for a device. A device's app writes its own platform name into the roster and many write none, this
 * app among them, so every device is drawn as the single handheld device unless the name is a computer's system.
 * No contract lists these names, so an unlisted computer is drawn as a handheld device, never the reverse.
 */
@DrawableRes
internal fun linkedDeviceIcon(platform: String): Int =
    when (platform.trim().lowercase(Locale.ROOT)) {
        "macos" -> R.drawable.ic_device_laptop
        "linux", "windows", "desktop" -> R.drawable.ic_device_desktop
        else -> R.drawable.ic_device
    }

/** A device glyph that heads the devices pages. It is decoration and is not read. */
@Composable
private fun DeviceHeaderGlyph(
    @DrawableRes icon: Int,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(HeaderGlyphSize),
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** The head of the devices page: the glyph for the account's devices above [detail], which says what linking does. */
@Composable
internal fun LinkedDevicesIntro(
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = WhiteNoiseSpacing.Related),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
    ) {
        DeviceHeaderGlyph(R.drawable.ic_devices)
        SettingsExplainer(detail)
    }
}

/**
 * The head of a device's page: its glyph above [name] and the facts MDK reports for [device]. The title bar also
 * carries [name] but can cut a long one short, so it is given in full here. The head is read as one item, and
 * holding it, or its Explain action, says what the facts mean.
 */
@Composable
internal fun LinkedDeviceHero(
    device: LinkedDeviceRow,
    name: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.SettingsSectionInset, vertical = WhiteNoiseSpacing.Related)
                .explainOnLongPress(Explanation(name, stringResource(R.string.explanation_linked_device_row)))
                .semantics(mergeDescendants = true) {}
                .testTag(DEVICE_DETAIL_HERO_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
    ) {
        DeviceHeaderGlyph(linkedDeviceIcon(device.platform))
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = linkedDeviceSummary(device).joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
