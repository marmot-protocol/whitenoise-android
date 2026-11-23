package dev.ipf.whitenoise.android.audio

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The finger's travel during an unlocked hold, published for the strip and the lock hint. It is view state only:
 * the gesture decides cancel and lock itself, and every capture transition resets it.
 */
@Stable
class VoiceHoldGestureState internal constructor() {
    /** Physical horizontal travel toward cancel, capped at the cancel threshold; zero on the other side. */
    var dragOffsetPx: Float by mutableFloatStateOf(0f)
        private set

    /** The last travel before a reset, so a cancelled strip leaves from where the finger let go. */
    var lastDragOffsetPx: Float = 0f
        private set

    /** Share of the cancel threshold travelled, from 0 to 1. */
    var cancelProgress: Float by mutableFloatStateOf(0f)
        private set

    /**
     * The physical side cancel lies on: -1 for left, 1 for right. It is the far side of the composer from the
     * microphone, so it follows RTL and a microphone placed at the start.
     */
    var cancelDirection: Float by mutableFloatStateOf(-1f)
        private set

    /** Vertical travel toward the lock target, never positive. */
    var verticalOffsetPx: Float by mutableFloatStateOf(0f)
        private set

    /** True while releasing here would discard the take. */
    var willCancel: Boolean by mutableStateOf(false)
        private set

    /** True while the finger is past the lock threshold and outside the cancel zone. */
    var willLock: Boolean by mutableStateOf(false)
        private set

    /** Points cancel at the physical side given by [direction] (negative for left). */
    internal fun aimCancel(direction: Float) {
        cancelDirection = if (direction < 0f) -1f else 1f
    }

    /** Travel toward the cancel side for a pointer [deltaX] from the down position; never negative. */
    internal fun cancelTravel(deltaX: Float): Float = (deltaX * cancelDirection).coerceAtLeast(0f)

    /** Records one pointer position relative to the down position. */
    internal fun update(
        deltaX: Float,
        deltaY: Float,
        cancelThresholdPx: Float,
        lockThresholdPx: Float,
    ) {
        val travel = cancelTravel(deltaX)
        dragOffsetPx = travel.coerceAtMost(cancelThresholdPx) * cancelDirection
        cancelProgress = (travel / cancelThresholdPx).coerceIn(0f, 1f)
        verticalOffsetPx = deltaY.coerceAtMost(0f)
        willCancel = travel > cancelThresholdPx
        // Match the actual lock gesture, which also requires staying out of the
        // cancel zone; otherwise the hint shows "armed" in the up+cancel overlap
        // region where release would actually cancel.
        willLock = (-deltaY) > lockThresholdPx && travel <= cancelThresholdPx
    }

    /** Returns the strip and hint to rest. */
    internal fun reset() {
        lastDragOffsetPx = dragOffsetPx
        dragOffsetPx = 0f
        cancelProgress = 0f
        verticalOffsetPx = 0f
        willCancel = false
        willLock = false
    }
}
