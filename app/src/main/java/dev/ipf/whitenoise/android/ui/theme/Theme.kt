package dev.ipf.whitenoise.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.state.resolveActionColorArgb

private const val OPAQUE_ARGB_MASK = 0xFFFFFFFFL

// Locked brand scheme — strictly monochrome Material roles over neutral surfaces,
// with a clean red reserved for errors. Every role is defined explicitly so nothing
// falls back to the M3 baseline (which is violet-tinted) when dynamic color is off.
// Colour enters only through a saved per-account accent, never through the base roles.
private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFF5F5F5),
        onPrimary = Color(0xFF171717),
        primaryContainer = Color(0xFF404040),
        onPrimaryContainer = Color(0xFFF5F5F5),
        inversePrimary = Color(0xFF171717),
        secondary = Color(0xFFD4D4D4),
        onSecondary = Color(0xFF262626),
        secondaryContainer = Color(0xFF404040),
        onSecondaryContainer = Color(0xFFF5F5F5),
        tertiary = Color(0xFFB8B8B8),
        onTertiary = Color(0xFF171717),
        tertiaryContainer = Color(0xFF404040),
        onTertiaryContainer = Color(0xFFF5F5F5),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF101010),
        onBackground = Color(0xFFF5F5F5),
        surface = Color(0xFF101010),
        onSurface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFF333333),
        onSurfaceVariant = Color(0xFFD4D4D4),
        surfaceTint = Color(0xFFF5F5F5),
        surfaceBright = Color(0xFF383838),
        surfaceDim = Color(0xFF101010),
        surfaceContainerLowest = Color(0xFF080808),
        surfaceContainerLow = Color(0xFF171717),
        surfaceContainer = Color(0xFF1E1E1E),
        surfaceContainerHigh = Color(0xFF262626),
        surfaceContainerHighest = Color(0xFF303030),
        outline = Color(0xFF999999),
        outlineVariant = Color(0xFF4D4D4D),
        inverseSurface = Color(0xFFE5E5E5),
        inverseOnSurface = Color(0xFF262626),
        scrim = Color(0xFF000000),
        primaryFixed = Color(0xFFE5E5E5),
        primaryFixedDim = Color(0xFFC7C7C7),
        onPrimaryFixed = Color(0xFF171717),
        onPrimaryFixedVariant = Color(0xFF4D4D4D),
        secondaryFixed = Color(0xFFE5E5E5),
        secondaryFixedDim = Color(0xFFC7C7C7),
        onSecondaryFixed = Color(0xFF171717),
        onSecondaryFixedVariant = Color(0xFF4D4D4D),
        tertiaryFixed = Color(0xFFE5E5E5),
        tertiaryFixedDim = Color(0xFFC7C7C7),
        onTertiaryFixed = Color(0xFF171717),
        onTertiaryFixedVariant = Color(0xFF4D4D4D),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF171717),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE5E5E5),
        onPrimaryContainer = Color(0xFF171717),
        inversePrimary = Color(0xFFF5F5F5),
        secondary = Color(0xFF4D4D4D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE5E5E5),
        onSecondaryContainer = Color(0xFF262626),
        tertiary = Color(0xFF666666),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE5E5E5),
        onTertiaryContainer = Color(0xFF262626),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE0DE),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF7F7F7),
        onBackground = Color(0xFF171717),
        surface = Color(0xFFF7F7F7),
        onSurface = Color(0xFF171717),
        surfaceVariant = Color(0xFFE5E5E5),
        onSurfaceVariant = Color(0xFF4D4D4D),
        surfaceTint = Color(0xFF171717),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDADADA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF3F3F3),
        surfaceContainer = Color(0xFFEFEFEF),
        surfaceContainerHigh = Color(0xFFE9E9E9),
        surfaceContainerHighest = Color(0xFFE3E3E3),
        outline = Color(0xFF737373),
        outlineVariant = Color(0xFFC7C7C7),
        inverseSurface = Color(0xFF262626),
        inverseOnSurface = Color(0xFFF5F5F5),
        scrim = Color(0xFF000000),
        primaryFixed = Color(0xFFE5E5E5),
        primaryFixedDim = Color(0xFFC7C7C7),
        onPrimaryFixed = Color(0xFF171717),
        onPrimaryFixedVariant = Color(0xFF4D4D4D),
        secondaryFixed = Color(0xFFE5E5E5),
        secondaryFixedDim = Color(0xFFC7C7C7),
        onSecondaryFixed = Color(0xFF171717),
        onSecondaryFixedVariant = Color(0xFF4D4D4D),
        tertiaryFixed = Color(0xFFE5E5E5),
        tertiaryFixedDim = Color(0xFFC7C7C7),
        onTertiaryFixed = Color(0xFF171717),
        onTertiaryFixedVariant = Color(0xFF4D4D4D),
    )

// AMOLED is a fixed black-and-white palette, layered by white outlines instead of
// tonal surfaces. Every ColorScheme role is overridden explicitly so new Material
// components cannot inherit a grey fallback from the base dark scheme, and a saved
// account accent is deliberately not applied on it. Media and avatars stay unfiltered.
private val AmoledColorScheme =
    DarkColorScheme.copy(
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color.Black,
        onPrimaryContainer = Color.White,
        secondaryContainer = Color.Black,
        onSecondaryContainer = Color.White,
        tertiaryContainer = Color.Black,
        onTertiaryContainer = Color.White,
        background = Color.Black,
        onBackground = Color.White,
        surface = Color.Black,
        onSurface = Color.White,
        surfaceVariant = Color.Black,
        surfaceTint = Color.Transparent,
        surfaceBright = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainerLowest = Color.Black,
        outline = AmoledEmphasizedSurfaceBorder,
        outlineVariant = AmoledSurfaceBorder,
        scrim = Color.Black,
    )

// Route the brand corner radii (Radii) through MaterialTheme.shapes so theme-aware
// M3 components (Button/Card/dialog/text-field/sheet) pick up consistent corners.
// extraSmall halves `sm` for the smallest chips; extraLarge is the 28 dp form and
// sheet corner shared with the tonal text field.
private val ShapeScheme =
    Shapes(
        extraSmall = RoundedCornerShape(Radii.sm / 2),
        small = RoundedCornerShape(Radii.sm),
        medium = RoundedCornerShape(Radii.md),
        large = RoundedCornerShape(Radii.lg),
        extraLarge = RoundedCornerShape(Radii.xxl),
    )

private fun ColorScheme.withAmoledPalette(amoledActive: Boolean): ColorScheme {
    if (!amoledActive) return this
    return AmoledColorScheme
}

private fun ColorScheme.withAccountAccent(
    accentColorArgb: Long?,
    amoledActive: Boolean,
): ColorScheme {
    // AMOLED keeps its fixed white action colour, the saved accent stays stored for the other themes.
    val resolvedAccent =
        accentColorArgb?.takeUnless { amoledActive }?.let {
            resolveActionColorArgb(
                customArgb = it,
                defaultContainerArgb = primary.toOpaqueArgb(),
                defaultContentArgb = onPrimary.toOpaqueArgb(),
                blueFree = false,
            )
        } ?: return this
    val accent = Color(resolvedAccent.container)
    val onAccent = Color(resolvedAccent.content)
    val safeInversePrimary =
        accent.takeIf {
            contrastRatio(it.toOpaqueArgb(), inverseSurface.toOpaqueArgb()) >= WCAG_AA_NORMAL_TEXT_CONTRAST
        } ?: inversePrimary
    return copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent,
        onPrimaryContainer = onAccent,
        inversePrimary = safeInversePrimary,
        // Material tonal elevation follows the active account accent.
        surfaceTint = accent,
    )
}

private fun Color.toOpaqueArgb(): Long = toArgb().toLong() and OPAQUE_ARGB_MASK

@Composable
fun WhiteNoiseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    // The active account's accent. A null value keeps the monochrome brand roles,
    // while a custom value drives primary actions and selected states outside AMOLED.
    accentColorArgb: Long? = null,
    // The app ships a locked brand palette, so dynamic (wallpaper-derived)
    // color is off by default. The path is kept for anyone who opts in.
    dynamicColor: Boolean = false,
    // In-app font-size step (#403). Multiplies sp typography sizes, which
    // already include the OS font scale, so it composes with the system
    // setting rather than replacing it.
    fontScale: Float = 1f,
    appFont: AppFont = AppFont.System,
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
    val amoledActive = darkTheme && amoled
    val colorScheme =
        baseColorScheme
            .withAmoledPalette(amoledActive)
            .withAccountAccent(accentColorArgb, amoledActive)

    CompositionLocalProvider(LocalAmoledSurfaceTheme provides amoledActive) {
        MaterialTheme(
            colorScheme = colorScheme,
            // Expressive spring-based motion for M3 components app-wide (M3E).
            motionScheme = MotionScheme.expressive(),
            shapes = ShapeScheme,
            typography = remember(fontScale, appFont) { Typography.withAppFont(appFont).scaledBy(fontScale) },
            content = content,
        )
    }
}
