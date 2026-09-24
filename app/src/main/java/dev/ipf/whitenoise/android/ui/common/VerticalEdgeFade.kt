package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Produces monotonic alpha-mask stops for content that dissolves at its vertical edges.
 *
 * [bottomInsetPx] reserves a strip painted underneath foreground chrome. Fades that would overlap
 * share the remaining clear height rather than inverting their stops.
 */
internal fun verticalEdgeFadeStops(
    heightPx: Float,
    topFadePx: Float,
    bottomFadePx: Float,
    bottomInsetPx: Float = 0f,
): List<Pair<Float, Color>>? {
    if (heightPx <= 0f) return null
    val inset = bottomInsetPx.coerceIn(0f, heightPx)
    val clear = heightPx - inset
    val requestedTop = topFadePx.coerceAtLeast(0f)
    val requestedBottom =
        bottomFadePx.coerceAtLeast(0f).let { if (inset > 0f) it.coerceAtLeast(1f) else it }
    val requested = requestedTop + requestedBottom
    val scale = if (requested > clear && requested > 0f) clear / requested else 1f
    val topBand = requestedTop * scale
    val bottomBand = requestedBottom * scale
    val topStop = (topBand / heightPx).coerceIn(0f, 1f)
    val bottomStop = ((clear - bottomBand) / heightPx).coerceIn(topStop, 1f)
    val clearStop = (clear / heightPx).coerceIn(bottomStop, 1f)
    return if (topBand <= 0f && bottomBand <= 0f) {
        null
    } else {
        buildList {
            if (topBand > 0f) {
                add(0f to Color.Transparent)
                add(topStop to Color.Black)
            } else {
                add(0f to Color.Black)
            }
            if (bottomBand > 0f) {
                add(bottomStop to Color.Black)
                add(clearStop to Color.Transparent)
            }
            if (inset > 0f) {
                add(1f to Color.Transparent)
            } else if (bottomBand <= 0f) {
                add(1f to Color.Black)
            }
        }
    }
}

/** Applies a theme-independent destination-in mask to the receiver's scrolling content. */
internal fun Modifier.verticalEdgeFade(
    topFade: Dp,
    bottomFade: Dp,
    bottomInset: Dp = 0.dp,
): Modifier = verticalEdgeFade(topFade = { topFade }, bottomFade = { bottomFade }, bottomInset = { bottomInset })

/** Applies a vertical edge mask while deferring animated values to the draw phase. */
internal fun Modifier.verticalEdgeFade(
    topFade: () -> Dp,
    bottomFade: () -> Dp,
    bottomInset: () -> Dp = { 0.dp },
): Modifier =
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val stops =
                verticalEdgeFadeStops(
                    heightPx = size.height,
                    topFadePx = topFade().toPx(),
                    bottomFadePx = bottomFade().toPx(),
                    bottomInsetPx = bottomInset().toPx(),
                ) ?: return@drawWithContent
            drawRect(
                brush = Brush.verticalGradient(colorStops = stops.toTypedArray()),
                blendMode = BlendMode.DstIn,
            )
        }
