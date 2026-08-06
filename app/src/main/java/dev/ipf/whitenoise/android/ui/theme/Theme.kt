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

// Route the existing brand corner radii (Radii) through MaterialTheme.shapes so
// theme-aware M3 components (Button/Card/dialog/text-field/sheet) pick up
// consistent corners instead of the violet-baseline defaults. Values mirror the
// current literal radii used at call sites — extraSmall halves `sm` for the
// smallest chips, and extraLarge maps to `xl` (24dp) rather than the M3 default
// 28dp so large sheets/containers match the rest of the brand scale.
private val ShapeScheme =
    Shapes(
        extraSmall = RoundedCornerShape(Radii.sm / 2),
        small = RoundedCornerShape(Radii.sm),
        medium = RoundedCornerShape(Radii.md),
        large = RoundedCornerShape(Radii.lg),
        extraLarge = RoundedCornerShape(Radii.xl),
    )

private fun ColorScheme.withAmoledSurfaces(amoledActive: Boolean): ColorScheme {
    if (!amoledActive) return this

    // Every full-screen and elevated surface stays pure black. Snackbars use
    // inverse roles, so keep those black/readable too (#446).
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
        surfaceVariant = Color.Black,
        surfaceBright = Color.Black,
        surfaceDim = Color.Black,
        outline = AmoledEmphasizedSurfaceBorder,
        outlineVariant = AmoledSurfaceBorder,
        inverseSurface = Color.Black,
        inverseOnSurface = onSurface,
        inversePrimary = Highlight,
        surfaceTint = Color.Transparent,
    )
}

private fun ColorScheme.withAccountAccent(
    accentColorArgb: Long?,
    amoledActive: Boolean,
): ColorScheme {
    val resolvedAccent =
        accentColorArgb?.let {
            resolveActionColorArgb(
                customArgb = it,
                defaultContainerArgb = primary.toOpaqueArgb(),
                defaultContentArgb = onPrimary.toOpaqueArgb(),
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
        // AMOLED elevation must remain untinted; other themes follow the
        // active account accent for Material tonal elevation.
        surfaceTint = if (amoledActive) Color.Transparent else accent,
    )
}

private fun Color.toOpaqueArgb(): Long = toArgb().toLong() and OPAQUE_ARGB_MASK

@Composable
fun WhiteNoiseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    // The active account's accent. A null value preserves the locked brand
    // cyan, while a custom value drives primary actions and selected states.
    accentColorArgb: Long? = null,
    // The app ships a locked brand palette, so dynamic (wallpaper-derived)
    // color is off by default. The path is kept for anyone who opts in.
    dynamicColor: Boolean = false,
    // In-app font-size step (#403). Multiplies sp typography sizes, which
    // already include the OS font scale, so it composes with the system
    // setting rather than replacing it.
    fontScale: Float = 1f,
    appFont: AppFont = AppFont.Manrope,
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
            .withAmoledSurfaces(amoledActive)
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
