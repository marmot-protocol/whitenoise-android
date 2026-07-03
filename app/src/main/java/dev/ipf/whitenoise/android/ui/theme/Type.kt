package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography =
    Typography(
        // Expressive display treatment for brand surfaces (onboarding hero name).
        // The M3 baseline leaves display* at Normal weight with slightly positive
        // tracking, which reads thin for a wordmark; the brand lockup wants a
        // tighter, more confident display. Sizes stay on the M3 display scale so
        // the in-app font-size step (#403, Typography.scaledBy) and the OS font
        // scale still compose cleanly. Call sites may still bump weight locally
        // (e.g. the landing name uses SemiBold).
        displayLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.25).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 45.sp,
                lineHeight = 52.sp,
                letterSpacing = 0.sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                letterSpacing = 0.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
     */
    )

/**
 * Scale every Material text style by the in-app font-size step (#403).
 * Explicit lineHeights scale together with fontSize so large steps don't clip
 * tall glyphs; em/unspecified units already track the font size and are left
 * alone. A factor of 1.0 returns this instance untouched so the Default step
 * is a true no-op (screenshot baselines depend on that).
 */
fun Typography.scaledBy(factor: Float): Typography {
    if (factor == 1f) return this

    fun TextUnit.scaled(): TextUnit = if (isSp) (value * factor).sp else this

    fun TextStyle.scaled(): TextStyle = copy(fontSize = fontSize.scaled(), lineHeight = lineHeight.scaled())
    return copy(
        displayLarge = displayLarge.scaled(),
        displayMedium = displayMedium.scaled(),
        displaySmall = displaySmall.scaled(),
        headlineLarge = headlineLarge.scaled(),
        headlineMedium = headlineMedium.scaled(),
        headlineSmall = headlineSmall.scaled(),
        titleLarge = titleLarge.scaled(),
        titleMedium = titleMedium.scaled(),
        titleSmall = titleSmall.scaled(),
        bodyLarge = bodyLarge.scaled(),
        bodyMedium = bodyMedium.scaled(),
        bodySmall = bodySmall.scaled(),
        labelLarge = labelLarge.scaled(),
        labelMedium = labelMedium.scaled(),
        labelSmall = labelSmall.scaled(),
    )
}
