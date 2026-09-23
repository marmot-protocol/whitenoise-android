package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.verticalEdgeFade
import dev.ipf.whitenoise.android.ui.common.verticalEdgeFadeStops

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
): List<Pair<Float, Color>>? = verticalEdgeFadeStops(heightPx, topFadePx, bottomFadePx, bottomInsetPx)

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
): Modifier = verticalEdgeFade(topFade = topFade, bottomFade = bottomFade, bottomInset = bottomInset)

/**
 * Dissolves the transcript into the chrome above it and beneath the composer below it.
 *
 * The bottom fade spans the composer itself: content stays at full strength until the composer's
 * top edge, then thins away to nothing by the bottom of the screen. Rows therefore slide under the
 * composer and dissolve there, rather than meeting a cut line. Because that band begins where the
 * content rests, the newest message is never dimmed and the bottom fade needs no gating.
 *
 * The top has no such luxury. The oldest message rests a few pixels under the bar, so an always-on
 * band would hold it permanently dimmed, and that edge fades only while older messages remain.
 *
 * [composerOverlap] is how far the transcript paints beneath the composer, and so is both where the
 * fade starts and how long it takes. A conversation with no overlap has nothing behind the composer
 * and gets no bottom fade.
 */
@Composable
internal fun Modifier.transcriptEdgeFade(
    listState: LazyListState,
    composerOverlap: Dp,
    fadeHeight: Dp = TRANSCRIPT_EDGE_FADE_HEIGHT,
): Modifier {
    // The transcript is reversed, so scrolling forward walks back through history towards the bar.
    val topFade by
        animateDpAsState(
            targetValue = if (listState.canScrollForward) fadeHeight else 0.dp,
            label = "transcript-top-fade",
        )
    return transcriptEdgeFade(topFade = topFade, bottomFade = composerOverlap, bottomInset = 0.dp)
}
