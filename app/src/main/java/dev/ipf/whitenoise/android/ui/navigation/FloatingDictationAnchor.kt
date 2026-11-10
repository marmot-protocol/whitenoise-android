@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Visible layout obstacles owned by the current route or persistent shell chrome. */
internal class FloatingDictationAnchor {
    private val bounds = mutableStateMapOf<Any, Rect>()
    private val composerFabObstacles = mutableStateMapOf<Any, Boolean>()
    private val composerControls = mutableStateMapOf<Any, Rect>()

    var composerControlColors: AccountActionColors? by mutableStateOf(null)
        private set
    val composerControlBounds: Rect? get() = composerControls.values.lastOrNull()

    /** Reports inline slot styling and removes a token whose measured slot has no area. */
    fun reportComposerControls(
        token: Any,
        rect: Rect,
        colors: AccountActionColors? = null,
    ) {
        composerControlColors = colors
        if (rect.width > 0f && rect.height > 0f) composerControls[token] = rect else composerControls.remove(token)
    }

    /** Releases one inline slot and clears its colors only after the final composer slot leaves. */
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

    val topInWindow: Float? get() = topInWindow(null)

    /**
     * The painted pen/pill registers its role directly, so placement rounding cannot change which
     * clock its edge follows. Separate status rows, notices and footers keep their real clearance.
     */
    fun topInWindow(movingComposerFab: Rect?): Float? =
        bounds.minOfOrNull { (token, rect) ->
            if (composerFabObstacles[token] == true && movingComposerFab != null) movingComposerFab.top else rect.top
        }

    /** Top of the highest obstacle sharing the horizontal span [left]..[right], or null when none does. */
    fun topInColumn(
        left: Float,
        right: Float,
    ): Float? = bounds.values.filter { it.left < right && it.right > left }.minOfOrNull { it.top }

    /**
     * Changes whenever an in-route obstacle such as a notice or footer registers or leaves, but not when
     * one merely moves. The capsule glides on the floating control clock only for these appear/leave
     * steps; a moving obstacle such as the keyboard or a scrolling footer keeps carrying it frame by frame.
     */
    var obstacleGeneration: Int = 0
        private set

    /**
     * Changes whenever a pen or composer registers or leaves. Those swap with the route, where the
     * navigation handoff owns the capsule's path, so the capsule follows them without its own glide.
     */
    var routeChromeGeneration: Int = 0
        private set

    /** Registers a visible obstacle and whether its painted edge follows the composer/FAB bridge. */
    fun report(
        token: Any,
        visibleBounds: Rect,
        followsComposerFab: Boolean = false,
    ) {
        if (visibleBounds.width > 0f && visibleBounds.height > 0f) {
            if (token !in bounds) bumpGeneration(followsComposerFab)
            bounds[token] = visibleBounds
            composerFabObstacles[token] = followsComposerFab
        } else {
            remove(token)
        }
    }

    private var floatingCapsule: FloatingCapsulePlacement? by mutableStateOf(null)

    /**
     * Window bounds where the capsule is painted, or null while no floating capsule is shown. During a
     * handoff this follows the moving capsule, including a hand-back to its origin composer, and an
     * inline composer control at rest is not floating and reads null.
     */
    val floatingCapsuleBounds: Rect? get() = floatingCapsule?.painted()

    /** Written only by the shell's placement pass, so route overlays read but never steer the capsule. */
    fun reportFloatingCapsule(placement: FloatingCapsulePlacement?) {
        floatingCapsule = placement
    }

    fun remove(token: Any) {
        val followedComposerFab = composerFabObstacles.remove(token) == true
        if (bounds.remove(token) != null) bumpGeneration(followedComposerFab)
        footerViewports.remove(token)
        capsuleSlots.remove(token)
        footerScrolls.remove(token)
    }

    private fun bumpGeneration(routeChrome: Boolean) {
        if (routeChrome) routeChromeGeneration++ else obstacleGeneration++
    }
}

internal val LocalFloatingDictationAnchor = staticCompositionLocalOf<FloatingDictationAnchor?> { null }

/** Attach to the actual bottom chrome, including its IME layout, rather than guessing its height. */
@Composable
internal fun Modifier.floatingDictationAnchor(
    topInset: Dp = 0.dp,
    followsComposerFab: Boolean = false,
): Modifier {
    val insetPx = with(LocalDensity.current) { topInset.toPx() }
    val anchor = LocalFloatingDictationAnchor.current
    val current = LocalControlHandoffEndpointCurrent.current
    val token = remember(anchor) { Any() }
    DisposableEffect(anchor, token, current) {
        if (!current) anchor?.remove(token)
        onDispose { anchor?.remove(token) }
    }
    // The shell places content before its overlay. Publish during that same placement pass,
    // before the capsule reads the obstacle, including the first navigation frame.
    return if (current) {
        onPlaced { coordinates ->
            val bounds = coordinates.boundsInWindow()
            anchor?.report(
                token,
                bounds.copy(top = (bounds.top + insetPx).coerceAtMost(bounds.bottom)),
                followsComposerFab,
            )
        }
    } else {
        this
    }
}

/**
 * Where the shell placed the dictation control. [motion] and [settle] are read lazily by each reader, so
 * a handoff or settling glide animating on the frame clock moves the published bounds without re-placing
 * the shell, and overlays stacked above the capsule follow it on that same clock.
 */
internal data class FloatingCapsulePlacement(
    val placed: Rect,
    val inline: Boolean,
    val motion: MeasuredControlHandoff?,
    val rtl: Boolean,
    val settle: State<Float>? = null,
) {
    /**
     * The painted rectangle: the placed one shifted by an active handoff and the settling glide, or null
     * for an inline control at rest.
     */
    fun painted(): Rect? {
        val shift = motion?.capsuleHandoffShift(rtl)
        val glide = settle?.value ?: 0f
        return when {
            shift != null -> placed.translate(shift.x, shift.y + glide)
            inline -> null
            else -> placed.translate(0f, glide)
        }
    }
}

/**
 * The layer offset an active handoff paints the capsule at, or null while no handoff runs. Commands keep
 * their logical trailing edge while recovery text changes the container width.
 */
internal fun MeasuredControlHandoff.capsuleHandoffShift(rtl: Boolean): Offset? {
    val target = targetBounds
    val moving = renderedBounds
    if (!active || target == null || moving == null) return null
    return Offset(
        x = if (rtl) moving.left - target.left else moving.right - target.right,
        y = moving.center.y - target.center.y,
    )
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
    val current = LocalControlHandoffEndpointCurrent.current
    val token = remember(anchor, space?.capsuleSlot) { Any() }
    val scroll = rememberScrollState()
    DisposableEffect(anchor, token, current) {
        if (!current) anchor?.remove(token)
        onDispose { anchor?.remove(token) }
    }
    val boundedActions =
        if (space == null) {
            Modifier
        } else {
            Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .heightIn(max = space.height)
                .onPlaced {
                    if (current && space.capsuleSlot > 0.dp) {
                        anchor?.reportFooter(token, viewport = it.boundsInWindow(), scroll = scroll)
                    }
                }.verticalScroll(scroll)
                .onPlaced {
                    if (current && space.capsuleSlot > 0.dp) {
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
    composerFabMotion: MeasuredControlHandoff? = null,
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
    val settle = rememberCapsuleSettle()
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
                val slot = it.height + FloatingControlGap.roundToPx()
                if (available < slot + 48.dp.roundToPx()) {
                    DictationFooterSpace(available.toDp(), slot.toDp())
                } else {
                    DictationFooterSpace((available - slot).toDp(), 0.dp)
                }
            }
        val body = subcompose("content", contentSlots.forSpace(footerSpace)).single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.place(0, 0)
            if (bubble == null) anchor.reportFloatingCapsule(null)
            if (bubble != null) {
                val inlineBounds = anchor.composerControlBounds.takeIf { inlineControl }
                // The composer slot reports during body placement, before its shell controls are placed.
                // Missing current slot geometry uses the existing safe capsule position, never stale bounds.
                // Chats and another chat share the Persistent owner, so there is no action
                // handoff to animate. Follow the already-drawn container on its existing clock.
                // Inline owner changes retain their own captured command trajectory instead.
                val movingObstacle =
                    composerFabMotion?.renderedBounds?.takeIf { !inlineControl && controlMotion?.active != true }
                val bottom =
                    anchor.topInWindow(movingObstacle)?.minus(rootTop)?.roundToInt()
                        ?: (constraints.maxHeight - fallbackInset)
                val availableGap = (bottom - bubble.height - safeTop).coerceIn(0, FloatingControlGap.roundToPx())
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
                // Only a pen, notice or footer arriving or leaving glides the capsule; keyboard frames,
                // scrolling footers and handoffs keep carrying it directly.
                val glide =
                    settle.offsetFor(
                        y = y,
                        generation = anchor.obstacleGeneration,
                        routeGeneration = anchor.routeChromeGeneration,
                        follow = inlineControl || scrolling != null || controlMotion?.active == true,
                    )
                val owner =
                    if (inlineControl) {
                        ConversationDictationControlOwner.Composer
                    } else {
                        ConversationDictationControlOwner.Persistent
                    }
                // Registration reads its previous geometry before writing it. Those bookkeeping
                // reads must not subscribe this placement to the map it updates every motion frame.
                val placed = Rect(rootLeft + x, rootTop + y, rootLeft + x + bubble.width, rootTop + y + bubble.height)
                Snapshot.withoutReadObservation {
                    controlMotion?.report(owner, controlToken, placed)
                    // Route overlays such as the conversation jump stack clear the painted position. An
                    // inline control publishes too, so a hand-back to the composer is cleared until it lands.
                    anchor.reportFloatingCapsule(
                        FloatingCapsulePlacement(
                            placed = placed,
                            inline = inlineControl,
                            motion = controlMotion,
                            rtl = layoutDirection == LayoutDirection.Rtl,
                            settle = settle.painted,
                        ),
                    )
                }
                val localClip =
                    scrolling?.takeUnless { inlineControl }?.second?.translate(-rootLeft - x, -rootTop - y)
                bubble.placeWithLayer(x, y) {
                    // No command, session or editor is duplicated by the handoff.
                    translationY = glide
                    controlMotion?.capsuleHandoffShift(layoutDirection == LayoutDirection.Rtl)?.let { shift ->
                        translationX = shift.x
                        translationY = shift.y + glide
                    }
                    if (localClip != null) {
                        clip = true
                        // Layer motion must not move the footer viewport in window coordinates.
                        val fixedClip = localClip.translate(-translationX, -translationY)
                        shape = GenericShape { _, _ -> addRect(fixedClip) }
                    }
                }
            }
        }
    }
}

/**
 * Glides the floating capsule from its previous slot when bottom chrome appears or leaves, on the floating
 * control clock. The published capsule bounds include [painted], so overlays stacked above the capsule
 * follow the glide frame by frame instead of running a clock of their own.
 */
private class CapsuleSettle(
    private val scope: CoroutineScope,
    private val motionEnabled: Boolean,
) {
    private val offset = Animatable(0f)

    /** The current glide, read lazily by whoever paints or stacks against the capsule. */
    val painted: State<Float> get() = offset.asState()
    private var lastY: Int? = null
    private var lastGeneration = 0
    private var lastRouteGeneration = 0

    /**
     * Layer offset to paint at for a capsule placed at [y]. A route chrome change or [follow] drops any
     * glide at once, so navigation and its handoff alone own the capsule's path.
     */
    fun offsetFor(
        y: Int,
        generation: Int,
        routeGeneration: Int,
        follow: Boolean,
    ): Float {
        val previous = lastY
        val chromeChanged = generation != lastGeneration
        val routeChanged = routeGeneration != lastRouteGeneration
        lastY = y
        lastGeneration = generation
        lastRouteGeneration = routeGeneration
        val glide = chromeChanged && !routeChanged && !follow && motionEnabled
        return when {
            // Rising clears new chrome in the same frame; only settling down glides.
            glide && previous != null && y > previous -> {
                val from = offset.value + (previous - y)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offset.snapTo(from)
                    offset.animateTo(0f, floatingControlMotionSpec())
                }
                from
            }
            (routeChanged || follow || previous != y) && offset.value != 0f -> {
                scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(0f) }
                0f
            }
            else -> offset.value
        }
    }
}

@Composable
private fun rememberCapsuleSettle(): CapsuleSettle {
    val scope = rememberCoroutineScope()
    val motionEnabled = rememberSystemAnimationsEnabled()
    return remember(scope, motionEnabled) { CapsuleSettle(scope, motionEnabled) }
}
