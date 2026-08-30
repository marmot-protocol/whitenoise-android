@file:Suppress("MatchingDeclarationName") // Link coordination and its row observer form one gesture contract.

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Defers link activation only while read-aloud owns the conversation. */
internal class TtsLinkTapCoordinator(
    private val scope: CoroutineScope,
    private val isReadAloudActive: () -> Boolean,
    private val doubleTapTimeoutMillis: Long,
) {
    private var pendingActivation: Job? = null
    private var pointerActivationArmed = false

    fun beginPointerActivation() {
        pointerActivationArmed = isReadAloudActive()
    }

    fun endPointerActivation() {
        pointerActivationArmed = false
    }

    fun activate(action: () -> Unit) {
        val defer = pointerActivationArmed && isReadAloudActive()
        pointerActivationArmed = false
        if (!defer) {
            action()
            return
        }
        pendingActivation?.cancel()
        pendingActivation =
            scope.launch {
                delay(doubleTapTimeoutMillis)
                action()
                pendingActivation = null
            }
    }

    fun cancelPendingActivation() {
        pointerActivationArmed = false
        pendingActivation?.cancel()
        pendingActivation = null
    }
}

/**
 * Observes unconsumed taps after child controls have had their turn. This keeps
 * links, mentions, media controls, scrolling, and reply swipes authoritative;
 * only two ordinary text taps close enough in time and space become a seek.
 */
@Composable
@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
internal fun Modifier.observeMessageTextDoubleTap(
    enabled: Boolean,
    allowConsumedTapAt: (Offset) -> Boolean,
    onPointerDown: (Offset) -> Unit,
    onPointerFinished: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
): Modifier {
    val currentAllowConsumedTapAt = rememberUpdatedState(allowConsumedTapAt)
    val currentOnPointerDown = rememberUpdatedState(onPointerDown)
    val currentOnPointerFinished = rememberUpdatedState(onPointerFinished)
    val currentOnDoubleTap = rememberUpdatedState(onDoubleTap)
    return if (!enabled) {
        this
    } else {
        // Callback refresh is state-backed, so sentence recomposition does not
        // restart gesture recognition or leave a stale closure behind.
        pointerInput(Unit) {
            var previousTapAt = 0L
            var previousTapPosition: Offset? = null
            awaitEachGesture {
                val down =
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Final,
                    )
                val allowConsumedTap = currentAllowConsumedTapAt.value(down.position)
                currentOnPointerDown.value(down.position)
                var movedTooFar = false
                var consumed = down.isConsumed
                var upAt = 0L
                var upPosition: Offset? = null
                while (upPosition == null) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    consumed = consumed || change.isConsumed
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        movedTooFar = true
                    }
                    if (!change.pressed) {
                        upAt = change.uptimeMillis
                        upPosition = change.position
                    }
                }
                val observedUp = upPosition
                currentOnPointerFinished.value()
                if (
                    observedUp == null ||
                    movedTooFar ||
                    (consumed && !allowConsumedTap)
                ) {
                    previousTapAt = 0L
                    previousTapPosition = null
                    return@awaitEachGesture
                }
                val elapsed = upAt - previousTapAt
                val previousPosition = previousTapPosition
                val closeEnough =
                    previousPosition != null &&
                        (observedUp - previousPosition).getDistance() <= viewConfiguration.touchSlop
                if (
                    closeEnough &&
                    elapsed in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis
                ) {
                    previousTapAt = 0L
                    previousTapPosition = null
                    currentOnDoubleTap.value(observedUp)
                } else {
                    previousTapAt = upAt
                    previousTapPosition = observedUp
                }
                // The observer never consumes pointer changes. Consumed child
                // gestures are ignored except links, whose activation is
                // deliberately deferred so the second tap can become a seek.
            }
        }
    }
}
