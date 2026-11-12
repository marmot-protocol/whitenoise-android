package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Picture
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

internal val SettingsCardMorphCorner = 20.dp

// Morph schedule in linear time. Arriving content fades in as its card starts to travel, leaving
// content fades out inside its card before the cards meet, and the travelling card's own fill hands
// over to the settled card over the last stretch so its edge never pops.
private const val CONTENT_IN_START = 0.04f
private const val CONTENT_IN_END = 0.24f
private const val CONTENT_OUT_END = 0.3f
private const val FOLD_CONTENT_OUT_END = 0.35f
private const val FOLD_BACKPLATE_END = 0.35f
private const val HANDOVER_START = 0.6f
private const val FOLD_HANDOVER_START = 0.25f
private const val FOLD_HANDOVER_END = 0.55f

/** Colors of the morph: the page [backplate] and each travelling card's [fill] and optional [outline]. */
internal data class SettingsMorphColors(
    val backplate: Color,
    val fill: Color,
    val outline: Color,
)

/** A card of the route being left: where it was and, when it had drawn, its last frame. */
internal class SettingsMorphCard(
    val bounds: Rect,
    val picture: Picture?,
)

internal fun settingsMorphContentIn(t: Float): Float {
    val span = CONTENT_IN_END - CONTENT_IN_START
    return ((t - CONTENT_IN_START) / span).coerceIn(0f, 1f)
}

internal fun settingsMorphContentOut(
    t: Float,
    folding: Boolean,
): Float = (1f - t / if (folding) FOLD_CONTENT_OUT_END else CONTENT_OUT_END).coerceIn(0f, 1f)

/** The route's backplate fading off the live route that cards fold back over. */
internal fun settingsMorphFoldBackplate(t: Float): Float = (1f - t / FOLD_BACKPLATE_END).coerceIn(0f, 1f)

/** Travelling card fill fading into the settled card, or, when folding, into the live anchor beneath it. */
internal fun settingsMorphHandover(
    t: Float,
    folding: Boolean,
): Float {
    // A fold must reveal the live anchor while the cards gather, never leave empty outlines over it.
    val start = if (folding) FOLD_HANDOVER_START else HANDOVER_START
    val end = if (folding) FOLD_HANDOVER_END else 1f
    return (1f - (t - start) / (end - start)).coerceIn(0f, 1f)
}

internal fun DrawScope.drawSettingsMorphBubble(
    bubble: Rect,
    colors: SettingsMorphColors,
    alpha: Float,
) {
    if (alpha <= 0f) return
    val corner = CornerRadius(SettingsCardMorphCorner.toPx())
    drawRoundRect(colors.fill, bubble.topLeft, bubble.size, corner, alpha = alpha)
    if (colors.outline != Color.Unspecified) {
        drawRoundRect(colors.outline, bubble.topLeft, bubble.size, corner, style = Stroke(1.dp.toPx()), alpha = alpha)
    }
}

/** Draws a leaving card's last frame inside its travelling [bubble], fitted to the bubble's width. */
internal fun DrawScope.drawSettingsMorphPicture(
    picture: Picture,
    card: Rect,
    bubble: Rect,
    alpha: Float,
) {
    if (alpha <= 0f || card.width <= 0f) return
    val scale = bubble.width / card.width
    val clip = Path().apply { addRoundRect(RoundRect(bubble, CornerRadius(SettingsCardMorphCorner.toPx()))) }
    clipPath(clip) {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bubble, Paint().apply { this.alpha = alpha })
            canvas.translate(bubble.left, bubble.top)
            canvas.scale(scale, scale)
            canvas.nativeCanvas.drawPicture(picture)
            canvas.restore()
        }
    }
}

/**
 * Moves an arriving card into its travelling card: scaled to its width from the top start corner
 * and clipped to its height and corners. [travel] is the settled bounds and travelling card.
 */
internal fun GraphicsLayerScope.applySettingsMorphTravel(travel: Pair<Rect, Rect>?) {
    val (settled, bubble) = travel ?: (Rect.Zero to Rect.Zero)
    if (settled.width <= 0f || bubble.width <= 0f) {
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
        shape = RectangleShape
        clip = false
        return
    }
    val scale = bubble.width / settled.width
    val height = bubble.height / scale
    val corner = SettingsCardMorphCorner.toPx() / scale
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = scale
    scaleY = scale
    translationX = bubble.left - settled.left
    translationY = bubble.top - settled.top
    shape = GenericShape { size, _ -> addRoundRect(RoundRect(0f, 0f, size.width, height, CornerRadius(corner))) }
    clip = true
}
