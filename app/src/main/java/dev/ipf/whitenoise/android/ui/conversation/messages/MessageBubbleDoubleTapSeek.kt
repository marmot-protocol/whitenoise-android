package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Observes unconsumed taps after child controls have had their turn. This keeps
 * links, mentions, media controls, scrolling, and reply swipes authoritative;
 * only two ordinary text taps close enough in time and space become a seek.
 */
internal fun Modifier.observeMessageTextDoubleTap(
    enabled: Boolean,
    onDoubleTap: (Offset) -> Unit,
): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(onDoubleTap) {
            var previousTapAt = 0L
            var previousTapPosition: Offset? = null
            awaitEachGesture {
                awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Final,
                )
                val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                if (up == null) {
                    previousTapAt = 0L
                    previousTapPosition = null
                    return@awaitEachGesture
                }
                val elapsed = up.uptimeMillis - previousTapAt
                val previousPosition = previousTapPosition
                val closeEnough =
                    previousPosition != null &&
                        (up.position - previousPosition).getDistance() <= viewConfiguration.touchSlop
                if (
                    closeEnough &&
                    elapsed in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis
                ) {
                    previousTapAt = 0L
                    previousTapPosition = null
                    onDoubleTap(up.position)
                } else {
                    previousTapAt = up.uptimeMillis
                    previousTapPosition = up.position
                }
                // The observer never consumes pointer changes. A tap that a
                // nested control owns remains that control’s tap.
            }
        }
    }
