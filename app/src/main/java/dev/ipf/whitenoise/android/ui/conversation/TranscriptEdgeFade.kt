package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How far a message dissolves before it reaches the chrome above or below it. */
internal val TRANSCRIPT_EDGE_FADE_HEIGHT: Dp = 28.dp

/**
 * The fade's stops as fractions of the transcript's height, or null when there is nothing to fade.
 *
 * [bottomInsetPx] is the strip the transcript paints underneath foreground chrome. The fade has to
 * finish where that strip begins, not at the transcript's own edge, or the gradient would be spent
 * in the covered region where nobody can see it.
 *
 * Kept separate from the drawing so the arithmetic can be tested: a fade taller than the space it
 * has, or one that would meet the opposite fade, has to degrade rather than invert the gradient.
 */
internal fun transcriptEdgeFadeStops(
    heightPx: Float,
    topFadePx: Float,
    bottomFadePx: Float,
    bottomInsetPx: Float = 0f,
): List<Pair<Float, Color>>? {
    if (heightPx <= 0f) return null
    val inset = bottomInsetPx.coerceIn(0f, heightPx)
    val clear = heightPx - inset
    val requestedTop = topFadePx.coerceAtLeast(0f)
    // A covered strip still needs a stop of its own, so that the content meets the chrome as a
    // clean cut even when no bottom fade was asked for.
    val requestedBottom =
        bottomFadePx.coerceAtLeast(0f).let { if (inset > 0f) it.coerceAtLeast(1f) else it }
    val requested = requestedTop + requestedBottom
    // Two fades that would overlap leave no readable middle, so share the clear height between them.
    val scale = if (requested > clear && requested > 0f) clear / requested else 1f
    val topBand = requestedTop * scale
    val bottomBand = requestedBottom * scale
    // Sharing the height can leave the two inner stops a rounding step apart in the wrong order,
    // which is the very inversion this is meant to avoid. Clamping each stop to the one before it
    // keeps the gradient monotonic whatever the arithmetic does at the edges.
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

/**
 * Dissolves the content towards the chrome at each edge.
 *
 * The content itself is masked rather than covered by a scrim: a scrim only disappears against a
 * background of a known flat colour, while a mask works over any theme, including a true-black
 * AMOLED surface, and leaves whatever is behind the bar untouched.
 */
internal fun Modifier.transcriptEdgeFade(
    topFade: Dp,
    bottomFade: Dp,
    bottomInset: Dp = 0.dp,
): Modifier =
    this
        // The mask blends against the layer rather than the screen, so the fade has to be composited
        // off-screen first; without this the destination-in blend erases everything behind it.
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val stops =
                transcriptEdgeFadeStops(
                    heightPx = size.height,
                    topFadePx = topFade.toPx(),
                    bottomFadePx = bottomFade.toPx(),
                    bottomInsetPx = bottomInset.toPx(),
                ) ?: return@drawWithContent
            drawRect(
                brush = Brush.verticalGradient(colorStops = stops.toTypedArray()),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * Dissolves the transcript into the chrome at whichever edge still has messages past it.
 *
 * An edge with nothing beyond it stays crisp. The newest message rests only [CONVERSATION_TIMELINE_TAIL_GAP]
 * above the composer and the oldest sits just under the bar, so a band that were always on would
 * hold both of them permanently dimmed, which reads as a rendering fault rather than as depth.
 *
 * [bottomInset] is the strip the transcript paints beneath the composer. Masking it is what makes
 * the covered rows agree with the rest of the screen, which already withholds them from touch,
 * from TalkBack and from the read watermark.
 */
@Composable
internal fun Modifier.transcriptEdgeFade(
    listState: LazyListState,
    bottomInset: Dp,
    fadeHeight: Dp = TRANSCRIPT_EDGE_FADE_HEIGHT,
): Modifier {
    // The transcript is reversed, so scrolling forward walks back through history towards the bar
    // and scrolling backward returns to the newest message against the composer.
    val topFade by
        animateDpAsState(
            targetValue = if (listState.canScrollForward) fadeHeight else 0.dp,
            label = "transcript-top-fade",
        )
    val bottomFade by
        animateDpAsState(
            targetValue = if (listState.canScrollBackward) fadeHeight else 0.dp,
            label = "transcript-bottom-fade",
        )
    return transcriptEdgeFade(topFade = topFade, bottomFade = bottomFade, bottomInset = bottomInset)
}
