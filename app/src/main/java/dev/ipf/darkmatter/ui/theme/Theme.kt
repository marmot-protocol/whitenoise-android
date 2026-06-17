package dev.ipf.darkmatter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Locked brand scheme — a monochrome-cyan palette over neutral surfaces. Every
// role is defined explicitly so nothing falls back to the M3 baseline (which is
// violet-tinted) when dynamic color is off. Chips/tallies (secondaryContainer)
// read as deep teal, accents (tertiary) stay in the cyan family, and the unread
// badge (error) is a clean red — the three roles that previously leaked the
// device wallpaper palette.
private val DarkColorScheme =
    darkColorScheme(
        primary = Highlight,
        onPrimary = OnHighlight,
        primaryContainer = Highlight,
        onPrimaryContainer = OnHighlight,
        secondary = Color(0xFF6BA3AD),
        onSecondary = Color(0xFF00363F),
        secondaryContainer = Color(0xFF1E3A40),
        onSecondaryContainer = Color(0xFFB8E7EF),
        tertiary = Color(0xFF7FD4E0),
        onTertiary = Color(0xFF003640),
        tertiaryContainer = Color(0xFF1E3A40),
        onTertiaryContainer = Color(0xFFC7EEF5),
        error = Color(0xFFFF5C5C),
        onError = Color(0xFF2A0000),
        errorContainer = Color(0xFF5C1A1A),
        onErrorContainer = Color(0xFFFFD9D6),
        background = Color(0xFF0F1112),
        onBackground = Color(0xFFE2E3E3),
        surface = Color(0xFF121414),
        onSurface = Color(0xFFE2E3E3),
        surfaceVariant = Color(0xFF3F4849),
        onSurfaceVariant = Color(0xFFBEC8C9),
        surfaceContainerLowest = Color(0xFF0C0E0E),
        surfaceContainerLow = Color(0xFF161818),
        surfaceContainer = Color(0xFF1A1D1D),
        surfaceContainerHigh = Color(0xFF242727),
        surfaceContainerHighest = Color(0xFF2F3232),
        outline = Color(0xFF899393),
        outlineVariant = Color(0xFF3F4849),
        inverseSurface = Color(0xFFE2E3E3),
        inverseOnSurface = Color(0xFF1A1D1D),
        scrim = Color(0xFF000000),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Highlight,
        onPrimary = OnHighlight,
        primaryContainer = Highlight,
        onPrimaryContainer = OnHighlight,
        secondary = Color(0xFF4A6268),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCDE7ED),
        onSecondaryContainer = Color(0xFF051F24),
        tertiary = Color(0xFF00696E),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFC7EEF5),
        onTertiaryContainer = Color(0xFF002023),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFECEEEE),
        onBackground = Color(0xFF191C1C),
        surface = Color(0xFFECEEEE),
        onSurface = Color(0xFF191C1C),
        surfaceVariant = Color(0xFFDBE4E5),
        onSurfaceVariant = Color(0xFF3F4849),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF3F4F4),
        surfaceContainer = Color(0xFFEDEFEF),
        surfaceContainerHigh = Color(0xFFE7E9E9),
        surfaceContainerHighest = Color(0xFFE1E3E3),
        outline = Color(0xFF6F7979),
        outlineVariant = Color(0xFFBFC8C9),
        inverseSurface = Color(0xFF2D3131),
        inverseOnSurface = Color(0xFFEFF1F1),
        scrim = Color(0xFF000000),
    )

@Composable
fun DarkMatterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    // The app ships a locked brand palette, so dynamic (wallpaper-derived)
    // color is off by default. The path is kept for anyone who opts in.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseColorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    val amoledAdjusted =
        if (darkTheme && amoled) {
            baseColorScheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color(0xFF050505),
                surfaceContainer = Color(0xFF0A0A0A),
                surfaceContainerHigh = Color(0xFF101010),
                surfaceContainerHighest = Color(0xFF161616),
                surfaceVariant = Color(0xFF1A1A1A),
                surfaceBright = Color(0xFF161616),
                surfaceDim = Color.Black,
            )
        } else {
            baseColorScheme
        }
    val colorScheme =
        amoledAdjusted.copy(
            primary = Highlight,
            onPrimary = OnHighlight,
            primaryContainer = Highlight,
            onPrimaryContainer = OnHighlight,
            surfaceTint = Highlight,
        )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
