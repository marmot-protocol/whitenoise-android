package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

private val labelGrays = (0..MAX_GRAY).map { Color(it, it, it) }
private const val MAX_GRAY = 255
private const val LIGHT_ON_DARK_GRAY = 153
private const val DARK_ON_LIGHT_GRAY = 102
private const val LUMINANCE_OFFSET = 0.05f
private const val MINIMUM_CONTRAST = 4.5f

/** Prefer a quiet gray for footer labels, moving only as far as needed to keep small text readable. */
internal fun messageFooterLabelColor(
    container: Color,
    content: Color,
): Color {
    val background = container.luminance()
    val preferred = if (content.luminance() > background) LIGHT_ON_DARK_GRAY else DARK_ON_LIGHT_GRAY
    return labelGrays
        .withIndex()
        .filter { (_, color) ->
            val foreground = color.luminance()
            (maxOf(background, foreground) + LUMINANCE_OFFSET) /
                (minOf(background, foreground) + LUMINANCE_OFFSET) >= MINIMUM_CONTRAST
        }.minBy { abs(it.index - preferred) }
        .value
}
