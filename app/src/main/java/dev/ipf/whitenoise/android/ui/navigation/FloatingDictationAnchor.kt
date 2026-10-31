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
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import kotlin.math.roundToInt

/** Only visible layout obstacles, including both sides of an animated route transition. */
internal class FloatingDictationAnchor {
    private val bounds = mutableStateMapOf<Any, Rect>()
    private val composerControls = mutableStateMapOf<Any, Rect>()

    var composerControlColors: AccountActionColors? by mutableStateOf(null)
        private set
    val composerControlBounds: Rect? get() = composerControls.values.lastOrNull()

    fun reportComposerControls(
        token: Any,
        rect: Rect,
        colors: AccountActionColors? = null,
    ) {
        composerControlColors = colors
        if (rect.width > 0f && rect.height > 0f) composerControls[token] = rect else composerControls.remove(token)
    }

    fun removeComposerControls(token: Any) {
        composerControls.remove(token)
        if (composerControls.isEmpty()) composerControlColors = null
    }

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

private val LocalDictationFooterSpace = compositionLocalOf<DictationFooterSpace?> { null }

/**
 * Reuses one shell-content lambda per footer space. Subcomposition recomposes only for a new lambda, so
 * remeasuring without dictation, or with unchanged space, leaves the shell's composition untouched.
 */
private class DictationContentSlots(
    private val content: @Composable () -> Unit,
) {
    private var space: DictationFooterSpace? = null
    private var slot: @Composable () -> Unit = provide(null)

    /** Returns the cached lambda while [next] equals the previous footer space. */
    fun forSpace(next: DictationFooterSpace?): @Composable () -> Unit {
        if (next != space) {
            space = next
            slot = provide(next)
        }
        return slot
    }

    /** Shell content sees [footerSpace] through [LocalDictationFooterSpace]. */
    private fun provide(footerSpace: DictationFooterSpace?): @Composable () -> Unit =
        { CompositionLocalProvider(LocalDictationFooterSpace provides footerSpace, content = content) }
}

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
    inlineControl: Boolean = false,
    controlMotion: MeasuredControlHandoff? = null,
) {
    val statusBars = WindowInsets.statusBars
    val ime = WindowInsets.ime
    val navigationBars = WindowInsets.navigationBars
    var rootTop by remember { mutableStateOf(0f) }
    var rootLeft by remember { mutableStateOf(0f) }
    val controlToken = remember(controlMotion) { Any() }
    val idleScroll = rememberScrollState()
    val controlSlot: @Composable () -> Unit =
        remember(anchor, control) {
            {
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
            }
        }
    val contentSlots = remember(content) { DictationContentSlots(content) }
    SubcomposeLayout(
        modifier =
            Modifier.fillMaxSize().onPlaced {
                rootTop = it.positionInWindow().y
                rootLeft = it.positionInWindow().x
            },
    ) { constraints ->
        // Insets are read while measuring, so a keyboard frame remeasures the shell without recomposing it.
        val statusBarTop = statusBars.getTop(this)
        val fallbackInset = maxOf(ime.getBottom(this), navigationBars.getBottom(this))
        val bubble =
            subcompose("control", controlSlot)
                .single()
                .measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxWidth =
                            if (inlineControl) {
                                anchor.composerControlBounds
                                    ?.width
                                    ?.roundToInt()
                                    ?.coerceIn(0, constraints.maxWidth)
                                    ?: constraints.maxWidth
                            } else {
                                constraints.maxWidth
                            },
                    ),
                ).takeIf { it.width > 0 && it.height > 0 }
        val safeTop = (statusBarTop - rootTop).roundToInt().coerceAtLeast(0)
        val footerSpace =
            bubble?.takeUnless { inlineControl }?.let {
                val available = (constraints.maxHeight - fallbackInset - safeTop).coerceAtLeast(0)
                val slot = it.height + 12.dp.roundToPx()
                if (available < slot + 48.dp.roundToPx()) {
                    DictationFooterSpace(available.toDp(), slot.toDp())
                } else {
                    DictationFooterSpace((available - slot).toDp(), 0.dp)
                }
            }
        val body = subcompose("content", contentSlots.forSpace(footerSpace)).single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.place(0, 0)
            if (bubble != null) {
                val inlineBounds = anchor.composerControlBounds.takeIf { inlineControl }
                // The composer slot reports during body placement, before its shell controls are placed.
                // Missing current slot geometry uses the existing safe capsule position, never stale bounds.
                val bottom = anchor.topInWindow?.minus(rootTop)?.roundToInt() ?: (constraints.maxHeight - fallbackInset)
                val availableGap = (bottom - bubble.height - safeTop).coerceIn(0, 12.dp.roundToPx())
                val scrolling = anchor.scrollingCapsule.takeIf { footerSpace?.capsuleSlot != 0.dp }
                val x =
                    inlineBounds?.let { (it.right - rootLeft - bubble.width).roundToInt() }
                        ?: (constraints.maxWidth - bubble.width - 16.dp.roundToPx()).coerceAtLeast(0)
                val y =
                    if (scrolling != null && !inlineControl) {
                        (scrolling.first.top - rootTop).roundToInt()
                    } else {
                        inlineBounds?.let { (it.top - rootTop).roundToInt() }
                            ?: (bottom - bubble.height - availableGap).coerceAtLeast(safeTop)
                    }
                val owner =
                    if (inlineControl) {
                        ConversationDictationControlOwner.Composer
                    } else {
                        ConversationDictationControlOwner.Persistent
                    }
                controlMotion?.report(
                    owner,
                    controlToken,
                    Rect(rootLeft + x, rootTop + y, rootLeft + x + bubble.width, rootTop + y + bubble.height),
                )
                if (scrolling == null || inlineControl) {
                    bubble.placeWithLayer(x, y) {
                        val target = controlMotion?.targetBounds
                        val moving = controlMotion?.renderedBounds
                        if (controlMotion?.active == true && target != null && moving != null) {
                            translationX = moving.left - target.left
                            translationY = moving.top - target.top
                        }
                    }
                } else {
                    val viewport = scrolling.second
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
