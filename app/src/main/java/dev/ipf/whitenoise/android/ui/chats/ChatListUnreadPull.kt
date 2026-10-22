package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** A pull must begin at the logical head; scrolling or flinging into it never arms the action. */
internal fun Modifier.chatListUnreadPull(
    enabled: Boolean,
    atTop: () -> Boolean,
    thresholdPx: Float,
    onProgress: (Float) -> Unit = {},
    scopeKey: Any? = Unit,
    onActivate: () -> Unit,
): Modifier =
    composed {
        val currentAtTop by rememberUpdatedState(atTop)
        val currentActivate by rememberUpdatedState(onActivate)
        val currentProgress by rememberUpdatedState(onProgress)
        pointerInput(enabled, thresholdPx, scopeKey) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val armed = currentAtTop()
                var distance = 0f
                var horizontal = 0f
                var rejected = !armed
                var claimed = false
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == first.id } ?: break
                        if (event.changes.size > 1 || change.isConsumed) rejected = true
                        // An untouched hold belongs to the row's context-menu/range-selection gesture.
                        // Claim only a pull that crossed touch slop before long press could take ownership.
                        val heldMillis = change.uptimeMillis - first.uptimeMillis
                        if (!claimed && heldMillis >= viewConfiguration.longPressTimeoutMillis) {
                            rejected = true
                        }
                        if (change.pressed) {
                            val delta = change.positionChange()
                            distance += delta.y
                            horizontal += delta.x
                            if (abs(horizontal) > viewConfiguration.touchSlop && abs(horizontal) > abs(distance)) {
                                rejected = true
                            }
                            if (distance < -viewConfiguration.touchSlop) rejected = true
                            if (!rejected && distance > viewConfiguration.touchSlop) {
                                claimed = true
                                change.consume()
                                currentProgress((distance / thresholdPx).coerceIn(0f, 1f))
                            }
                        } else {
                            if (!rejected && distance >= thresholdPx) currentActivate()
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    currentProgress(0f)
                }
            }
        }
    }
