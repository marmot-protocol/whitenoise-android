@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
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
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.util.Locale

internal const val DEVICE_DETAIL_HERO_TAG = "device_detail.hero"

private val EmblemSize = 80.dp
private val EmblemGlyphSize = 40.dp

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

/**
 * A device glyph on the nine-sided emblem that heads the devices pages. It is decoration and is not read. The
 * emblem takes the primary pair because every container role is black on the AMOLED theme's black page.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeviceEmblem(
    @DrawableRes icon: Int,
) {
    Box(
        modifier =
            Modifier
                .size(EmblemSize)
                .background(MaterialTheme.colorScheme.primary, MaterialShapes.Cookie9Sided.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(EmblemGlyphSize),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** The head of the devices page: the emblem for the account's devices above [detail], which says what linking does. */
@Composable
internal fun LinkedDevicesIntro(
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.SettingsSectionInset, vertical = WhiteNoiseSpacing.Related),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
    ) {
        DeviceEmblem(R.drawable.ic_devices)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The head of a device's page: its glyph on the emblem above the facts MDK reports for [device]. The page's title
 * already carries [name], so it is not repeated here. The facts are read as one item, and holding it, or its
 * Explain action, says what they mean.
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
        DeviceEmblem(linkedDeviceIcon(device.platform))
        Text(
            text = linkedDeviceSummary(device).joinToString("\n"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
