@file:Suppress("MatchingDeclarationName") // Pointer modifier and its small arbitration state share one implementation.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** Waits for directional intent before consuming, and permanently abandons disabled or vertical starts. */
internal class DirectionalSwipeIntent(
    private val slop: Float,
    private val left: Boolean,
    private val right: Boolean,
) {
    var x = 0f
        private set
    var y = 0f
        private set
    var direction = 0
        private set
    var cancelled = false
        private set

    /** Returns true only once this enabled, mostly-horizontal stream owns movement. */
    fun move(
        dx: Float,
        dy: Float,
    ): Boolean {
        x += dx
        y += dy
        if (!cancelled && direction == 0) {
            if (abs(x) > slop) {
                direction = if (x < 0) -1 else 1
            } else if (abs(y) > slop) {
                cancelled = true
            }
        }
        if (direction != 0) {
            val enabled = if (direction < 0) left else right
            val horizontal = abs(x) > abs(y) * HORIZONTAL_DOMINANCE
            if (!enabled || !horizontal || x * direction <= 0) cancelled = true
        }
        return !cancelled && direction != 0
    }
}

/** No detector is mounted when both bindings are off. Keys revoke an in-flight target or setting change. */
internal fun Modifier.directionalSwipe(
    owner: Any?,
    settings: Any?,
    left: Boolean,
    right: Boolean,
    onDistance: (Float, Int) -> Unit,
    onRelease: (Int) -> Unit,
    onCancel: () -> Unit,
    canRecognize: (Int) -> Boolean = { true },
): Modifier =
    if (!left && !right) {
        this
    } else {
        pointerInput(owner, settings, left, right) {
            detectDirectionalSwipe(left, right, onDistance, onRelease, onCancel, canRecognize)
        }
    }

/** Shares pointer arbitration between message rows and opt-in conversation rows, without dispatching commands. */
@Suppress("LoopWithTooManyJumpStatements") // Cancel, release and claimed movement are distinct pointer outcomes.
private suspend fun PointerInputScope.detectDirectionalSwipe(
    left: Boolean,
    right: Boolean,
    onDistance: (Float, Int) -> Unit,
    onRelease: (Int) -> Unit,
    onCancel: () -> Unit,
    canRecognize: (Int) -> Boolean,
) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!canRecognize(0)) return@awaitEachGesture
            val intent = DirectionalSwipeIntent(viewConfiguration.touchSlop, left, right)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.exclusiveSwipeChange(down.id)
                if (change == null) {
                    onCancel()
                    break
                }
                if (!canRecognize(intent.direction)) {
                    onCancel()
                    break
                }
                if (!change.pressed) {
                    intent.release(onRelease, onCancel)
                    break
                }
                val delta = change.position - change.previousPosition
                if (intent.move(delta.x, delta.y)) {
                    if (!canRecognize(intent.direction)) {
                        onCancel()
                        break
                    }
                    change.consume()
                    onDistance(abs(intent.x), intent.direction)
                } else if (intent.cancelled) {
                    onCancel()
                    break
                }
            }
        }
    } finally {
        onCancel()
    }
}

private const val HORIZONTAL_DOMINANCE = 1.2f

/** Another recognizer or a second pressed pointer revokes this stream before horizontal consumption. */
private fun PointerEvent.exclusiveSwipeChange(id: PointerId): PointerInputChange? =
    changes.firstOrNull { it.id == id }?.takeUnless {
        it.isConsumed || changes.count { pointer -> pointer.pressed } > 1
    }

/** An unclaimed or abandoned stream never dispatches a release action. */
private fun DirectionalSwipeIntent.release(
    onRelease: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    if (direction != 0 && !cancelled) onRelease(direction) else onCancel()
}
