package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.PublicKeyCopyText
import dev.ipf.whitenoise.android.ui.common.animatePublicKeyExpansion
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

@Composable
@Suppress("FunctionNaming")
internal fun SettingsExpandedProfileKey(npub: String) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
    PublicKeyCopyText(
        publicKeyDisplayGroups(npub).joinToString(" "),
        npub,
        Modifier
            .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
            .padding(bottom = WhiteNoiseSpacing.Related)
            .animatePublicKeyExpansion(expanded = true),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = Int.MAX_VALUE,
        keyboardFocusable = true,
    )
}

/** QR glyph and chevron that announce the profile row as the way to Share & Connect. */
@Composable
@Suppress("FunctionNaming")
internal fun SettingsProfileTrailing() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings_qr_code_2),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
