package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import dev.ipf.whitenoise.android.ui.common.drawAtAlpha

/** Below this presence an origin container shows no route ink; closing, the ink is gone by then. */
internal const val SETTINGS_CONTAINER_BODY_FADE_START = 0.22f

/** Route ink is fully native from this presence on. */
internal const val SETTINGS_CONTAINER_BODY_FADE_END = 0.7f

/** A container appears leaving its origin and, closing, disappears into it over this much presence. */
private const val SETTINGS_CONTAINER_FADE = 0.2f

/** Paint for a borderless route container. */
internal data class SettingsContainerColors(
    val backplate: Color,
    val fill: Color,
)

/** Route ink inside an origin container: none early, fully native well before rest. Shared by both directions. */
internal fun settingsContainerBodyAlpha(presence: Float): Float {
    val span = SETTINGS_CONTAINER_BODY_FADE_END - SETTINGS_CONTAINER_BODY_FADE_START
    return ((presence - SETTINGS_CONTAINER_BODY_FADE_START) / span).coerceIn(0f, 1f)
}

/** The empty container fades in leaving its origin and out landing in it, revealing the live control. */
internal fun settingsContainerShapeAlpha(presence: Float): Float = (presence / SETTINGS_CONTAINER_FADE).coerceIn(0f, 1f)

/**
 * Ink on an open that resumed at [startPresence] from a close that drew none (no picture was
 * permitted): absent on the first reopened frame, blending into the [normal] ink as the motion
 * continues. Without a resumed start it is [normal] itself.
 */
internal fun settingsResumedBodyAlpha(
    normal: Float,
    presence: Float,
    startPresence: Float?,
): Float {
    if (startPresence == null || startPresence >= 1f) return normal
    return normal * ((presence - startPresence) / (1f - startPresence)).coerceIn(0f, 1f)
}

/** One rounded container: its fill, then [content] clipped to it at native coordinates. */
internal fun DrawScope.drawContainer(
    shape: RoundRect,
    shapeAlpha: Float,
    bodyAlpha: Float,
    colors: SettingsContainerColors,
    content: (DrawScope.() -> Unit)?,
) {
    if (shapeAlpha <= 0f) return
    val clip = Path().apply { addRoundRect(shape) }
    drawPath(clip, colors.fill, alpha = shapeAlpha)
    val body = content?.takeIf { bodyAlpha > 0f } ?: return
    clipPath(clip) { drawAtAlpha(shape.boundingRect, bodyAlpha, body) }
}
