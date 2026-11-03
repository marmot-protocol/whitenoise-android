package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.MAX_VIEWPORT_FADE_FRACTION
import dev.ipf.whitenoise.android.ui.common.scrollEdgeFade
import dev.ipf.whitenoise.android.ui.common.scrollEdgeFadeState
import dev.ipf.whitenoise.android.ui.common.verticalEdgeFade
import dev.ipf.whitenoise.android.ui.common.verticalEdgeFadeOpaqueRange
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
 * What the transcript's mask is painting towards, published by the composable that paints it.
 *
 * Carries the live list state rather than a snapshot of its edges, so readers observe the fade
 * switching on and off at the same moment the mask does.
 */
internal data class TranscriptEdgeFadeSpec(
    val listState: LazyListState,
    val fadeHeightPx: Float,
    val composerOverlapPx: Float,
    val masking: Boolean,
)

/**
 * The transcript mask's fully opaque interval, owned by the same modifier that paints the mask.
 *
 * Read aloud must place a sentence where every pixel of it is legible, so it excludes the top band
 * through its full-opacity edge instead of guessing an offset. The band animates in, and this reports
 * the interval once the band has settled: the painted band is never taller than that, so a sentence
 * placed below it stays clear through the whole transition rather than chasing every frame. Until a
 * mask publishes here, nothing is faded and the whole height is opaque.
 */
@Stable
internal class TranscriptEdgeFadeGeometry {
    private var spec by mutableStateOf<TranscriptEdgeFadeSpec?>(null)

    /** Records the mask the owning modifier painted in this composition. */
    fun publish(spec: TranscriptEdgeFadeSpec) {
        this.spec = spec
    }

    /** The interval, in px from the top of a [heightPx]-tall transcript, left at full opacity. */
    fun opaqueRangePx(heightPx: Float): ClosedFloatingPointRange<Float>? {
        val spec = spec
        val listState = spec?.listState
        return when {
            heightPx <= 0f -> null
            spec == null || listState == null -> 0f..heightPx
            spec.composerOverlapPx > 0f ->
                verticalEdgeFadeOpaqueRange(
                    heightPx = heightPx,
                    topFadePx = if (listState.transcriptTopFadeActive()) spec.fadeHeightPx else 0f,
                    bottomFadePx = spec.composerOverlapPx,
                )
            else -> {
                // The ordinary reversed-list cues: the same edge policy and band cap scrollEdgeFade paints with.
                val edges = scrollEdgeFadeState(listState.canScrollBackward, listState.canScrollForward, true)
                verticalEdgeFadeOpaqueRange(
                    heightPx = heightPx,
                    topFadePx = if (spec.masking && edges.top) spec.fadeHeightPx else 0f,
                    bottomFadePx = if (spec.masking && edges.bottom) spec.fadeHeightPx else 0f,
                    maxBandFraction = MAX_VIEWPORT_FADE_FRACTION,
                )
            }
        }
    }
}

/** The transcript is reversed, so the top fades only while older history remains beyond it. */
private fun LazyListState.transcriptTopFadeActive(): Boolean = canScrollForward

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
 * and uses the ordinary reversed-list edge cues instead.
 *
 * [geometry] receives the mask this paints, so read aloud can keep sentences inside its opaque interval.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun Modifier.transcriptEdgeFade(
    listState: LazyListState,
    composerOverlap: Dp,
    fadeHeight: Dp = TRANSCRIPT_EDGE_FADE_HEIGHT,
    geometry: TranscriptEdgeFadeGeometry? = null,
): Modifier {
    val density = LocalDensity.current
    if (composerOverlap <= 0.dp) {
        // Matches scrollEdgeFade, which suspends its mask under the keyboard.
        val masking = !WindowInsets.isImeVisible
        geometry?.let { owner ->
            val spec = TranscriptEdgeFadeSpec(listState, with(density) { fadeHeight.toPx() }, 0f, masking)
            SideEffect { owner.publish(spec) }
        }
        return scrollEdgeFade(listState, reverseLayout = true, fadeHeight = fadeHeight)
    }
    geometry?.let { owner ->
        val spec =
            with(density) { TranscriptEdgeFadeSpec(listState, fadeHeight.toPx(), composerOverlap.toPx(), true) }
        SideEffect { owner.publish(spec) }
    }
    // The transcript is reversed, so scrolling forward walks back through history towards the bar.
    val topFade by
        animateDpAsState(
            targetValue = if (listState.transcriptTopFadeActive()) fadeHeight else 0.dp,
            label = "transcript-top-fade",
        )
    return verticalEdgeFade(
        topFade = { if (listState.transcriptTopFadeActive()) topFade else 0.dp },
        bottomFade = { composerOverlap },
    )
}
