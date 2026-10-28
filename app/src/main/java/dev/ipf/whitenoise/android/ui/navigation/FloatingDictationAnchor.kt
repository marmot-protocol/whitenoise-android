@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Only visible layout obstacles, including both sides of an animated route transition. */
internal class FloatingDictationAnchor {
    private val bounds = mutableStateMapOf<Any, Rect>()
    private val footerViewports = mutableStateMapOf<Any, Rect>()
    private val capsuleSlots = mutableStateMapOf<Any, Rect>()
    private val footerScrolls = mutableMapOf<Any, ScrollState>()

    /** A short footer scrolls one reserved slot with its actions; the shell still owns the capsule. */
    val scrollingCapsule: Pair<Rect, Rect>?
        get() =
            selectedFooter()?.let { (token, viewport) ->
                capsuleSlots[token]?.let { slot -> slot to viewport }
            }

    /** Publishes a measured clipping viewport separately from the un-clipped scrolling slot. */
    fun reportFooter(
        token: Any,
        viewport: Rect? = null,
        slot: Rect? = null,
        scroll: ScrollState? = null,
    ) {
        if (viewport != null) {
            if (viewport.width <= 0f || viewport.height <= 0f) {
                remove(token)
                return
            }
            footerViewports[token] = viewport
        }
        if (slot != null) capsuleSlots[token] = slot
        if (scroll != null) footerScrolls[token] = scroll
    }

    /** Both hit regions use the same scroll mutex, so a new drag cancels the other region's fling. */
    val scrollingState: ScrollState?
        get() = selectedFooter()?.key?.let(footerScrolls::get)

    /** Incomplete or offscreen outgoing registrations cannot hide the current control. */
    private fun selectedFooter(): Map.Entry<Any, Rect>? =
        footerViewports.entries
            .filter { (token, viewport) ->
                viewport.width > 0f && viewport.height > 0f && token in capsuleSlots && token in footerScrolls
            }.minByOrNull { it.value.top }

    val topInWindow: Float? get() = bounds.values.minOfOrNull { it.top }

    fun report(
        token: Any,
        visibleBounds: Rect,
    ) {
        if (visibleBounds.width > 0f && visibleBounds.height > 0f) {
            bounds[token] = visibleBounds
        } else {
            remove(token)
        }
    }

    fun remove(token: Any) {
        bounds.remove(token)
        footerViewports.remove(token)
        capsuleSlots.remove(token)
        footerScrolls.remove(token)
    }
}

internal val LocalFloatingDictationAnchor = staticCompositionLocalOf<FloatingDictationAnchor?> { null }

/** Attach to the actual bottom chrome, including its IME layout, rather than guessing its height. */
@Composable
internal fun Modifier.floatingDictationAnchor(): Modifier {
    val anchor = LocalFloatingDictationAnchor.current
    val token = remember(anchor) { Any() }
    DisposableEffect(anchor, token) { onDispose { anchor?.remove(token) } }
    // The shell places content before its overlay. Publish during that same placement pass,
    // before the capsule reads the obstacle, including the first navigation frame.
    return onPlaced { coordinates -> anchor?.report(token, coordinates.boundsInWindow()) }
}

/** Insets are consumed outside the action viewport; a short viewport scrolls the capsule slot too. */
private data class DictationFooterSpace(
    val height: Dp,
    val capsuleSlot: Dp,
)

private val LocalDictationFooterSpace = staticCompositionLocalOf<DictationFooterSpace?> { null }

/**
 * Registers the complete footer. Ordinary windows scroll only overflowing actions. Short windows
 * scroll a measured empty capsule slot and the actions together, retaining every full-size target.
 * The one real capsule remains in the shell and is clipped to this viewport as the slot scrolls.
 */
@Composable
internal fun Modifier.floatingDictationFooter(): Modifier {
    val space = LocalDictationFooterSpace.current
    val anchor = LocalFloatingDictationAnchor.current
    val token = remember(anchor, space?.capsuleSlot) { Any() }
    val scroll = rememberScrollState()
    DisposableEffect(anchor, token) { onDispose { anchor?.remove(token) } }
    val boundedActions =
        if (space == null) {
            Modifier
        } else {
            Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .heightIn(max = space.height)
                .onPlaced {
                    if (space.capsuleSlot > 0.dp) {
                        anchor?.reportFooter(token, viewport = it.boundsInWindow(), scroll = scroll)
                    }
                }.verticalScroll(scroll)
                .onPlaced {
                    if (space.capsuleSlot > 0.dp) {
                        val origin = it.positionInWindow()
                        anchor?.reportFooter(
                            token,
                            slot = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height),
                        )
                    }
                }.layout { measurable, constraints ->
                    val actions = measurable.measure(constraints)
                    val slot = space.capsuleSlot.roundToPx()
                    layout(actions.width, actions.height + slot) { actions.placeRelative(0, slot) }
                }
        }
    return floatingDictationAnchor().then(boundedActions)
}

/**
 * Measures the real capsule before the stable content slot. Footer owners receive its remaining
 * height in the same measure pass, so wrapped actions never spend a visible frame under the capsule.
 * No dictation state, draft, destination or route lifetime is transferred into this layout.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // Content, capsule and clipping share one placement pass.
internal fun FloatingDictationLayout(
    anchor: FloatingDictationAnchor,
    content: @Composable () -> Unit,
    control: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val fallbackInset = maxOf(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density))
    var rootTop by remember { mutableStateOf(0f) }
    val idleScroll = rememberScrollState()
    SubcomposeLayout(
        modifier = Modifier.fillMaxSize().onPlaced { rootTop = it.positionInWindow().y },
    ) { constraints ->
        val bubble =
            subcompose("control") {
                Box(
                    Modifier.scrollable(
                        state = anchor.scrollingState ?: idleScroll,
                        orientation = Orientation.Vertical,
                        enabled = anchor.scrollingState != null,
                        reverseDirection = true,
                    ),
                ) {
                    control()
                }
            }.single()
                .measure(constraints.copy(minWidth = 0, minHeight = 0))
                .takeIf { it.width > 0 && it.height > 0 }
        val safeTop = (statusBarTop - rootTop).roundToInt().coerceAtLeast(0)
        val footerSpace =
            bubble?.let {
                val available = (constraints.maxHeight - fallbackInset - safeTop).coerceAtLeast(0)
                val slot = it.height + 12.dp.roundToPx()
                if (available < slot + 48.dp.roundToPx()) {
                    DictationFooterSpace(available.toDp(), slot.toDp())
                } else {
                    DictationFooterSpace((available - slot).toDp(), 0.dp)
                }
            }
        val body =
            subcompose("content") {
                CompositionLocalProvider(LocalDictationFooterSpace provides footerSpace, content = content)
            }.single()
                .measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.place(0, 0)
            if (bubble != null) {
                val bottom = anchor.topInWindow?.minus(rootTop)?.roundToInt() ?: (constraints.maxHeight - fallbackInset)
                val availableGap = (bottom - bubble.height - safeTop).coerceIn(0, 12.dp.roundToPx())
                val scrolling = anchor.scrollingCapsule.takeIf { footerSpace?.capsuleSlot != 0.dp }
                val x = (constraints.maxWidth - bubble.width - 16.dp.roundToPx()).coerceAtLeast(0)
                if (scrolling == null) {
                    bubble.place(x, (bottom - bubble.height - availableGap).coerceAtLeast(safeTop))
                } else {
                    val (slot, viewport) = scrolling
                    val y = (slot.top - rootTop).roundToInt()
                    val localClip = viewport.translate(-x.toFloat(), -rootTop - y)
                    bubble.placeWithLayer(x, y) {
                        clip = true
                        shape = GenericShape { _, _ -> addRect(localClip) }
                    }
                }
            }
        }
    }
}
