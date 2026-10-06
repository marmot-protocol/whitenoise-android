package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import kotlin.math.abs
import kotlin.math.sign

/** An ancestor observes the border while descendants retain their complete tap targets. */
internal suspend fun PointerInputScope.detectComposerResizeFromTop(
    topHeightPx: () -> Float,
    onStarted: () -> Unit,
    onDragged: (PointerInputChange, Float) -> Unit,
    onStopped: (Boolean) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.y < 0f || down.position.y >= topHeightPx()) return@awaitEachGesture
        var accumulatedY = 0f
        var ownsDrag = false
        var completed = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    completed = !change.isConsumed
                    break
                }
                val delta = change.positionChangeIgnoreConsumed().y
                accumulatedY += delta
                var dragDelta = delta
                if (!ownsDrag && abs(accumulatedY) > viewConfiguration.touchSlop) {
                    ownsDrag = true
                    onStarted()
                    dragDelta = accumulatedY - sign(accumulatedY) * viewConfiguration.touchSlop
                }
                if (ownsDrag) {
                    onDragged(change, dragDelta)
                    change.consume()
                }
            }
        } finally {
            if (ownsDrag) onStopped(completed)
        }
    }
}
