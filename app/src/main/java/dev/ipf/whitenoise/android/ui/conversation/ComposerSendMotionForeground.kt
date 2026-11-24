package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

private const val TEXT_CONTRAST_OFFSET = 0.05f

/** Keep ink readable while the surface changes tone; interpolating opposite inks crosses zero contrast. */
internal fun composerMotionForeground(
    source: Color,
    destination: Color,
    fill: Color,
): Color {
    val backgroundLuminance = fill.luminance()

    /** WCAG contrast ratio of [foreground], composited over the fill, against that fill. */
    fun contrast(foreground: Color): Float {
        val inkLuminance = foreground.compositeOver(fill).luminance()
        return (maxOf(inkLuminance, backgroundLuminance) + TEXT_CONTRAST_OFFSET) /
            (minOf(inkLuminance, backgroundLuminance) + TEXT_CONTRAST_OFFSET)
    }
    return if (contrast(source) >= contrast(destination)) source else destination
}
