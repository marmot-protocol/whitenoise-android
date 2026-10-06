package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

internal val ComposerResizeStripHeight = 12.dp

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
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    completed = change?.isConsumed == false
                    return@awaitEachGesture
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

/** Observes the border as a parent, so compact controls keep every pixel of their tap targets. */
internal data class ComposerResizeCallbacks(
    val started: () -> Unit,
    val dragged: (Float) -> Unit,
    val stopped: () -> Unit,
    val settled: ((Float) -> Unit)?,
    val cancelled: (() -> Unit)?,
)

@Composable
internal fun Modifier.composerResizeGestures(
    enabled: Boolean,
    ownerKey: Any?,
    callbacks: ComposerResizeCallbacks,
): Modifier {
    val latestCallbacks by rememberUpdatedState(callbacks)
    var gestureCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { gestureCoordinates = it }
        .pointerInput(ownerKey, enabled) {
            if (!enabled) return@pointerInput
            val velocityTracker = VelocityTracker()
            detectComposerResizeFromTop(
                topHeightPx = { ComposerResizeStripHeight.toPx() },
                onStarted = {
                    velocityTracker.resetTracking()
                    latestCallbacks.started()
                },
                onDragged = { change, dragAmount ->
                    val rootPosition = gestureCoordinates?.localToRoot(change.position) ?: change.position
                    velocityTracker.addPosition(change.uptimeMillis, rootPosition)
                    latestCallbacks.dragged(dragAmount)
                },
                onStopped = { completed ->
                    if (!completed) {
                        (latestCallbacks.cancelled ?: latestCallbacks.stopped)()
                    } else {
                        val settle = latestCallbacks.settled
                        if (settle != null) settle(velocityTracker.calculateVelocity().y) else latestCallbacks.stopped()
                    }
                },
            )
        }
}
