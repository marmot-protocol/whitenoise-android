package dev.ipf.whitenoise.android.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal val SCROLL_EDGE_FADE_HEIGHT = 28.dp
private const val EDGE_FADE_ANIMATION_MILLIS = 120
private const val MAX_VIEWPORT_FADE_FRACTION = 0.25f

/** Physical viewport edges with more content beyond them; RTL does not invert vertical scrolling. */
internal data class ScrollEdgeFadeState(
    val top: Boolean,
    val bottom: Boolean,
)

internal fun scrollEdgeFadeState(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    reverseScrolling: Boolean = false,
): ScrollEdgeFadeState =
    if (reverseScrolling) {
        ScrollEdgeFadeState(top = canScrollForward, bottom = canScrollBackward)
    } else {
        ScrollEdgeFadeState(top = canScrollBackward, bottom = canScrollForward)
    }

/** Masks the bounded viewport, before its scroll modifier, without changing scroll ownership. */
@Composable
internal fun Modifier.scrollEdgeFade(
    state: ScrollState,
    reverseScrolling: Boolean = false,
    fadeEnabled: Boolean = true,
): Modifier {
    val source = remember(state) { ScrollEdgeSource(state, { state.canScrollBackward }, { state.canScrollForward }) }
    return scrollEdgeFade(source, reverseScrolling, fadeEnabled, SCROLL_EDGE_FADE_HEIGHT)
}

/** List callers keep their own state, restoration, keys and gestures. */
@Composable
internal fun Modifier.scrollEdgeFade(
    state: LazyListState,
    reverseLayout: Boolean = false,
    fadeHeight: Dp = SCROLL_EDGE_FADE_HEIGHT,
): Modifier {
    val source = remember(state) { ScrollEdgeSource(state, { state.canScrollBackward }, { state.canScrollForward }) }
    return scrollEdgeFade(source, reverseLayout, true, fadeHeight)
}

/** Grid callers keep their own state and layout; only viewport painting changes. */
@Composable
internal fun Modifier.scrollEdgeFade(
    state: LazyGridState,
    reverseLayout: Boolean = false,
): Modifier {
    val source = remember(state) { ScrollEdgeSource(state, { state.canScrollBackward }, { state.canScrollForward }) }
    return scrollEdgeFade(source, reverseLayout, true, SCROLL_EDGE_FADE_HEIGHT)
}

private class ScrollEdgeSource(
    val owner: Any,
    val backward: () -> Boolean,
    val forward: () -> Boolean,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Modifier.scrollEdgeFade(
    source: ScrollEdgeSource,
    reverseScrolling: Boolean,
    fadeEnabled: Boolean,
    fadeHeight: Dp,
): Modifier {
    // Keyboard forms keep ordinary painting and their caller-owned scroll state.
    // Remove the added layer immediately; hiding the IME restores the same viewport's cues.
    if (!fadeEnabled || WindowInsets.isImeVisible) return this
    val top = remember(source.owner, reverseScrolling, fadeEnabled) { Animatable(0.dp, Dp.VectorConverter) }
    val bottom = remember(source.owner, reverseScrolling, fadeEnabled) { Animatable(0.dp, Dp.VectorConverter) }
    LaunchedEffect(source.owner, reverseScrolling, fadeEnabled, fadeHeight) {
        snapshotFlow { scrollEdgeFadeState(source.backward(), source.forward(), reverseScrolling) }
            .collectLatest { edges ->
                coroutineScope {
                    launch { top.updateEdge(edges.top && fadeEnabled, fadeHeight) }
                    launch { bottom.updateEdge(edges.bottom && fadeEnabled, fadeHeight) }
                }
            }
    }
    return verticalEdgeFade(
        // Reaching an edge must reveal the resting content immediately, even before the coroutine runs.
        topFade = {
            if (fadeEnabled && scrollEdgeFadeState(source.backward(), source.forward(), reverseScrolling).top) {
                top.value
            } else {
                0.dp
            }
        },
        bottomFade = {
            if (fadeEnabled && scrollEdgeFadeState(source.backward(), source.forward(), reverseScrolling).bottom) {
                bottom.value
            } else {
                0.dp
            }
        },
        maxBandFraction = MAX_VIEWPORT_FADE_FRACTION,
    )
}

private suspend fun Animatable<Dp, *>.updateEdge(
    visible: Boolean,
    fadeHeight: Dp,
) {
    if (visible) {
        animateTo(fadeHeight, tween(EDGE_FADE_ANIMATION_MILLIS))
    } else {
        snapTo(0.dp)
    }
}
