package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import kotlin.math.abs

/** A visible lazy-list item expressed in the list's local coordinate space. */
internal data class DragSelectionVisibleItem<T>(
    val key: T,
    val start: Float,
    val end: Float,
)

/**
 * Select the inclusive anchor-to-endpoint interval without toggling rows as the
 * pointer crosses them. Items that are not in [eligibleIds] remain holes in the
 * visual interval and are skipped from the result.
 */
internal fun <T> anchoredDragSelection(
    orderedIds: List<T>,
    eligibleIds: Set<T>,
    anchorId: T,
    endpointId: T,
): Set<T> {
    val anchorIndex = orderedIds.indexOf(anchorId)
    val endpointIndex = orderedIds.indexOf(endpointId)
    if (anchorIndex < 0 || endpointIndex < 0 || anchorId !in eligibleIds) return emptySet()
    val range = minOf(anchorIndex, endpointIndex)..maxOf(anchorIndex, endpointIndex)
    return range.mapNotNullTo(linkedSetOf()) { index ->
        orderedIds[index].takeIf(eligibleIds::contains)
    }
}

/** The row under [pointerY], or the nearest visible row while dragging at an edge. */
internal fun <T> dragSelectionEndpoint(
    visibleItems: List<DragSelectionVisibleItem<T>>,
    pointerY: Float,
): T? =
    visibleItems
        .firstOrNull { pointerY >= it.start && pointerY <= it.end }
        ?.key
        ?: visibleItems
            .minByOrNull { item ->
                abs(pointerY - ((item.start + item.end) / 2f))
            }?.key

/**
 * A bounded per-frame auto-scroll delta. The speed ramps toward the viewport
 * edge so a small overshoot remains controllable while a deliberate edge hold
 * continues traversing the list.
 */
internal fun dragSelectionAutoScrollDelta(
    pointerY: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edgeThreshold: Float,
    maxStep: Float,
): Float {
    if (viewportEnd <= viewportStart || edgeThreshold <= 0f || maxStep <= 0f) return 0f
    val upperEdge = viewportStart + edgeThreshold
    val lowerEdge = viewportEnd - edgeThreshold
    return when {
        pointerY < upperEdge -> {
            val strength = ((upperEdge - pointerY) / edgeThreshold).coerceIn(0f, 1f)
            -maxStep * strength
        }
        pointerY > lowerEdge -> {
            val strength = ((pointerY - lowerEdge) / edgeThreshold).coerceIn(0f, 1f)
            maxStep * strength
        }
        else -> 0f
    }
}

/**
 * Preserve ordinary taps and pre-timeout scrolling, then resolve a completed
 * hold as either a stationary long press or a vertical range-selection drag.
 * Movement before the timeout remains available to scrolling and sibling
 * gestures such as swipe-to-reply. After the hold wins, this detector owns the
 * pointer through release.
 */
@Composable
internal fun Modifier.longPressOrVerticalDrag(
    enabled: Boolean = true,
    onLongPressStart: (Offset) -> Unit = {},
    onLongPressRelease: (Offset) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Boolean,
    onDragEnd: () -> Unit,
    onGestureCancel: () -> Unit,
): Modifier {
    val currentLongPressStart by rememberUpdatedState(onLongPressStart)
    val currentLongPressRelease by rememberUpdatedState(onLongPressRelease)
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    val currentGestureCancel by rememberUpdatedState(onGestureCancel)
    val pinnableContainer = LocalPinnableContainer.current
    return if (!enabled) {
        this
    } else {
        pointerInput(pinnableContainer) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                longPress.consume()
                val origin = longPress.position
                var dragging = false
                var rangeActivated = false
                var horizontalGestureWon = false
                var terminalCallbackDelivered = false
                var pinnedHandle: PinnableContainer.PinnedHandle? = null

                try {
                    // Lazy lists normally dispose rows after they leave the
                    // viewport. Pin the originating row for the gesture so edge
                    // auto-scroll cannot orphan the pointer before release.
                    pinnedHandle = pinnableContainer?.pin()
                    currentLongPressStart(origin)

                    while (true) {
                        val change =
                            awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: run {
                                    terminalCallbackDelivered = true
                                    currentGestureCancel()
                                    break
                                }
                        val displacement = change.position - origin
                        if (!dragging && !horizontalGestureWon) {
                            val verticalDistance = abs(displacement.y)
                            val horizontalDistance = abs(displacement.x)
                            when {
                                verticalDistance > viewConfiguration.touchSlop &&
                                    verticalDistance >= horizontalDistance -> {
                                    dragging = true
                                    currentDragStart(origin)
                                    rangeActivated = currentDrag(change.position)
                                }
                                horizontalDistance > viewConfiguration.touchSlop &&
                                    horizontalDistance > verticalDistance -> {
                                    horizontalGestureWon = true
                                }
                            }
                        } else if (dragging) {
                            rangeActivated = currentDrag(change.position) || rangeActivated
                        }

                        // The hold has won. Consume every remaining change,
                        // including the final up, so a stationary long press cannot
                        // fall through to a parent/child clickable on release.
                        change.consume()
                        if (change.changedToUpIgnoreConsumed()) {
                            terminalCallbackDelivered = true
                            when {
                                dragging && rangeActivated -> currentDragEnd()
                                dragging -> {
                                    currentGestureCancel()
                                    currentLongPressRelease(origin)
                                }
                                horizontalGestureWon -> currentGestureCancel()
                                else -> currentLongPressRelease(origin)
                            }
                            break
                        }
                        if (!change.pressed) {
                            terminalCallbackDelivered = true
                            currentGestureCancel()
                            break
                        }
                    }
                } finally {
                    try {
                        // Detachment, navigation, or a dataset replacement can
                        // cancel pointerInput without an up event. Always retire
                        // screen-owned drag and auto-scroll state in that case.
                        if (dragging && !terminalCallbackDelivered) currentGestureCancel()
                    } finally {
                        pinnedHandle?.release()
                    }
                }
            }
        }
    }
}
