@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * One stacking rule for every bottom-end floating control. From the top: jump controls, then the
 * floating dictation capsule, then the pen or composer. Each layer keeps [FloatingControlGap] above the
 * next one that is present, and when a layer leaves, the layers above it settle down on the shared clock.
 */

/** Vertical gap between stacked floating controls and between the lowest one and its bottom chrome. */
internal val FloatingControlGap = 8.dp

/** One clock for floating controls appearing, leaving and making room for each other. */
internal const val FLOATING_CONTROL_MOTION_MILLIS = 180

internal fun <T> floatingControlMotionSpec(): FiniteAnimationSpec<T> =
    tween(FLOATING_CONTROL_MOTION_MILLIS, easing = FastOutSlowInEasing)

/**
 * Grows a floating control out of its bottom-end corner and shrinks it back there, while the stack
 * closes the gap on the same clock. Reduced motion shows and removes the control at once.
 */
@Composable
internal fun FloatingControlVisibility(
    visible: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // The stack's end edge is the left one in RTL.
    val origin = TransformOrigin(if (LocalLayoutDirection.current == LayoutDirection.Rtl) 0f else 1f, 1f)
    val spec = floatingControlMotionSpec<Float>()
    val size = floatingControlMotionSpec<IntSize>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter =
            if (motionEnabled) {
                fadeIn(spec) + scaleIn(spec, initialScale = 0.6f, transformOrigin = origin) +
                    expandVertically(size, expandFrom = Alignment.Bottom)
            } else {
                EnterTransition.None
            },
        exit =
            if (motionEnabled) {
                fadeOut(spec) + scaleOut(spec, targetScale = 0.6f, transformOrigin = origin) +
                    shrinkVertically(size, shrinkTowards = Alignment.Bottom)
            } else {
                ExitTransition.None
            },
    ) {
        // A leaving control can no longer be tapped or reached by accessibility services.
        Box(if (visible) Modifier else InertControl) { content() }
    }
}

private val InertControl =
    Modifier
        .clearAndSetSemantics {}
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }

/**
 * Lifts a bottom-aligned route overlay so it sits [gap] above the shell's floating dictation capsule
 * whenever the two share horizontal space. With [clearColumnObstacles] it also stays [gap] above any
 * registered bottom chrome in its column, such as the chat list's pen, so the overlay can rest at the
 * bottom edge and stack above whichever of the capsule and pen is present.
 *
 * The read is one-way — the overlay never registers as an obstacle, so the capsule cannot chase the
 * overlay it just moved. Rising to clear new chrome happens in the same frame; settling back down glides
 * on the floating control clock, and reduced motion settles at once. It never carries the overlay above
 * its parent's top edge: when a tall capsule leaves too little room — recovery text with the keyboard
 * open, a short landscape window — the overlay rises only as far as its parent allows rather than
 * leaving the screen.
 */
@Composable
internal fun Modifier.floatingDictationCapsuleClearance(
    gap: Dp = FloatingControlGap,
    clearColumnObstacles: Boolean = false,
    motionEnabled: Boolean = rememberSystemAnimationsEnabled(),
): Modifier {
    val anchor = LocalFloatingDictationAnchor.current ?: return this
    val scope = rememberCoroutineScope()
    val lift = remember { Animatable(0f) }
    val target = remember { IntArray(1) { UNSETTLED } }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            val origin = coordinates?.positionInWindow()
            val wanted =
                if (origin == null) {
                    0
                } else {
                    val overlay = Rect(origin.x, origin.y, origin.x + placeable.width, origin.y + placeable.height)
                    floatingControlStackClearancePx(
                        overlay = overlay,
                        capsule = anchor.floatingCapsuleBounds,
                        obstacleTop =
                            if (clearColumnObstacles) anchor.topInColumn(overlay.left, overlay.right) else null,
                        gapPx = gap.toPx(),
                        topLimit = coordinates?.parentLayoutCoordinates?.positionInWindow()?.y ?: origin.y,
                    )
                }
            if (wanted != target[0]) {
                val first = target[0] == UNSETTLED
                val rising = wanted > lift.value
                target[0] = wanted
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    if (first || rising || !motionEnabled) {
                        lift.snapTo(wanted.toFloat())
                    } else {
                        lift.animateTo(wanted.toFloat(), floatingControlMotionSpec())
                    }
                }
            }
            // Rising is immediate so the overlay never overlaps the capsule or pen for a frame; only
            // settling back down after they shrink or leave glides on the shared clock.
            placeable.place(0, -maxOf(lift.value.roundToInt(), wanted))
        }
    }
}

private const val UNSETTLED = Int.MIN_VALUE

/**
 * Upward shift that leaves [gapPx] between [overlay] and [capsule], or zero when they never meet. The
 * shift is capped so the overlay's top never rises above [topLimit].
 */
internal fun floatingCapsuleClearancePx(
    overlay: Rect,
    capsule: Rect,
    gapPx: Float,
    topLimit: Float = Float.NEGATIVE_INFINITY,
): Int = floatingControlStackClearancePx(overlay, capsule, obstacleTop = null, gapPx = gapPx, topLimit = topLimit)

/**
 * Upward shift that leaves [gapPx] between [overlay] and the highest of [capsule] (when it shares the
 * overlay's column) and [obstacleTop] (already filtered to that column), or zero when neither is in the
 * way. The shift is capped so the overlay's top never rises above [topLimit].
 */
internal fun floatingControlStackClearancePx(
    overlay: Rect,
    capsule: Rect?,
    obstacleTop: Float?,
    gapPx: Float,
    topLimit: Float = Float.NEGATIVE_INFINITY,
): Int {
    val capsuleTop = capsule?.takeIf { overlay.left < it.right && overlay.right > it.left }?.top
    val ceiling = listOfNotNull(capsuleTop, obstacleTop).minOrNull()
    if (overlay.width <= 0f || ceiling == null) return 0
    val wanted = ceil(overlay.bottom + gapPx - ceiling).toInt().coerceAtLeast(0)
    val room = (overlay.top - topLimit).toInt().coerceAtLeast(0)
    return minOf(wanted, room)
}
