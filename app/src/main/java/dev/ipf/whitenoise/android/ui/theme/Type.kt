package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFont

val Manrope =
    FontFamily(
        Font(R.font.manrope_medium, FontWeight.Medium),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
        Font(R.font.manrope_bold, FontWeight.Bold),
    )

// A variable font carries its whole weight axis in one file; instantiate the
// weights the typography actually uses.
private fun variableFamily(resId: Int): FontFamily =
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
            Font(
                resId = resId,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
            )
        },
    )

private val Outfit = variableFamily(R.font.outfit_variable)
private val Urbanist = variableFamily(R.font.urbanist_variable)
private val Figtree = variableFamily(R.font.figtree_variable)

/** The [FontFamily] a Settings font choice maps to; null keeps the system face the scale is built on. */
fun AppFont.fontFamilyOrNull(): FontFamily? =
    when (this) {
        AppFont.System -> null
        AppFont.Manrope -> Manrope
        AppFont.Outfit -> Outfit
        AppFont.Urbanist -> Urbanist
        AppFont.Figtree -> Figtree
    }

/** Re-bases the whole scale onto the chosen app font; the system face is the built-in default. */
fun Typography.withAppFont(font: AppFont): Typography = font.fontFamilyOrNull()?.let { applyFontFamily(it) } ?: this

/** Applies [family] to every style of a [Typography] so the whole scale shares one font. */
private fun Typography.applyFontFamily(family: FontFamily): Typography =
    copy(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )

/**
 * The Material 3 baseline scale on the system face, with headlines, titles and labels at Medium
 * weight so hierarchy reads without size jumps. A chosen app font re-bases every style.
 */
val Typography = Typography().withMediumHeadings()

/** Headlines, titles and labels sit at Medium weight so hierarchy reads without size jumps. */
private fun Typography.withMediumHeadings(): Typography =
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Medium),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Medium),
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
