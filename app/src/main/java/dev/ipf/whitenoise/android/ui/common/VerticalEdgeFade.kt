package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Foundation scroll containers permit 30dp of sideways shadow overflow. Keep it while masking.
private val SCROLL_SHADOW_OUTSET = 30.dp
private val SCROLL_LAYER_OUTSETS = LayerOutsets(left = SCROLL_SHADOW_OUTSET, right = SCROLL_SHADOW_OUTSET)

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
    maxBandFraction: Float = 1f,
): List<Pair<Float, Color>>? {
    if (heightPx <= 0f) return null
    val inset = bottomInsetPx.coerceIn(0f, heightPx)
    val clear = heightPx - inset
    // Preserve proportional legacy composer geometry; ordinary viewports opt into the tighter cap.
    val bandLimit = if (maxBandFraction >= 1f) Float.POSITIVE_INFINITY else clear * maxBandFraction.coerceIn(0f, 1f)
    val requestedTop = topFadePx.coerceIn(0f, bandLimit)
    val requestedBottom =
        bottomFadePx.coerceIn(0f, bandLimit).let { if (inset > 0f) it.coerceAtLeast(1f) else it }
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
    maxBandFraction: Float = 1f,
    stableRenderTarget: Boolean = false,
): Modifier =
    this
        .graphicsLayer {
            outsets = SCROLL_LAYER_OUTSETS
            // Popup callers retain one render target while their animated edge bands change.
            compositingStrategy =
                when {
                    stableRenderTarget -> CompositingStrategy.Offscreen
                    topFade() > 0.dp || bottomFade() > 0.dp || bottomInset() > 0.dp -> CompositingStrategy.Offscreen
                    else -> CompositingStrategy.Auto
                }
        }.drawWithCache {
            val stops =
                verticalEdgeFadeStops(
                    heightPx = size.height,
                    topFadePx = topFade().toPx(),
                    bottomFadePx = bottomFade().toPx(),
                    bottomInsetPx = bottomInset().toPx(),
                    maxBandFraction = maxBandFraction,
                )
            val brush = stops?.let { Brush.verticalGradient(colorStops = it.toTypedArray()) }
            onDrawWithContent {
                drawContent()
                brush?.let {
                    val shadowOutset = SCROLL_SHADOW_OUTSET.toPx()
                    drawRect(
                        brush = it,
                        topLeft = Offset(-shadowOutset, 0f),
                        size = Size(size.width + 2 * shadowOutset, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
        }
