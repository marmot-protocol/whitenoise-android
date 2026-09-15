package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

private val GroupAuthorLightPalette =
    listOf(
        0xFFB3261E,
        0xFF9A4600,
        0xFF2E6B2F,
        0xFF006B5F,
        0xFF00639A,
        0xFF4F5AA8,
        0xFF7D5260,
        0xFF984061,
        0xFF765849,
    )
private val GroupAuthorDarkPalette =
    listOf(
        0xFFFFB4AB,
        0xFFFFB77D,
        0xFFA8D5A2,
        0xFF53DBC7,
        0xFF8ECBFF,
        0xFFBEC2FF,
        0xFFFFB0C8,
        0xFFFFB0C8,
        0xFFE8BEAA,
    )

/** The prototype's per-author colour for group sender names, chosen by the author's key and the surface. */
@Composable
internal fun groupAuthorColor(seed: String): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = if (dark) GroupAuthorDarkPalette else GroupAuthorLightPalette
    return Color(palette[seed.sumOf(Char::code) % palette.size])
}
