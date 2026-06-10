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

private val DarkColorScheme =
    darkColorScheme(
        primary = Primary80,
        secondary = Secondary80,
        tertiary = Tertiary80,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Primary40,
        secondary = Secondary40,
        tertiary = Tertiary40,
    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
     */
    )

@Composable
fun DarkMatterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
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
