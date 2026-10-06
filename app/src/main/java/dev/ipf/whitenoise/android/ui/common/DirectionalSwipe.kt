@file:Suppress("MatchingDeclarationName") // Pointer modifier and its small arbitration state share one implementation.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
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
): Modifier =
    if (!left && !right) {
        this
    } else {
        pointerInput(owner, settings, left, right) {
            detectDirectionalSwipe(left, right, onDistance, onRelease, onCancel)
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
) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val intent = DirectionalSwipeIntent(viewConfiguration.touchSlop, left, right)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || event.changes.count { it.pressed } > 1 || change.isConsumed) {
                    onCancel()
                    break
                }
                if (!change.pressed) {
                    if (intent.direction != 0 && !intent.cancelled) onRelease(intent.direction) else onCancel()
                    break
                }
                val delta = change.position - change.previousPosition
                if (intent.move(delta.x, delta.y)) {
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
