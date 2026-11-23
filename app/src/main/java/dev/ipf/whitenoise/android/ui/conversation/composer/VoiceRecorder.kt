package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/**
 * Hold-to-record voice button. Press → start; release → stop and review.
 * Slide 120dp toward the far side of the composer and release → cancel;
 * slide 80dp up → lock, so the take continues hands-free. A tap starts a
 * locked take. The gesture stays as a pointerInput input so Compose doesn't
 * fight us for the up event.
 */
@Composable
internal fun MicHoldButton(
    controller: dev.ipf.whitenoise.android.audio.VoiceRecordingController,
    modifier: Modifier = Modifier,
    slotWidth: Dp = ComposerToolbarSlotWidth,
    canStart: () -> Boolean = { true },
    onHoldStart: () -> Unit = {},
) {
    val latestHoldStart by rememberUpdatedState(onHoldStart)
    val latestCanStart by rememberUpdatedState(canStart)
    val haptics = LocalHapticFeedback.current
    val cancelThresholdDp = 120.dp
    val lockThresholdDp = 80.dp
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { cancelThresholdDp.toPx() }
    val lockThresholdPx = with(density) { lockThresholdDp.toPx() }
    val recording = controller.isRecording
    val label = stringResource(if (recording) R.string.voice_recording_stop else R.string.voice_message_record)
    ComposerToolbarTargets {
        IconButton(
            // Accessibility fallback: a tap (TalkBack double-tap, keyboard
            // Enter, switch access) toggles record-and-lock so users who can't
            // perform the press-and-hold gesture can still send voice notes.
            onClick = {
                if (controller.isRecording) {
                    controller.stop()
                } else if (latestCanStart()) {
                    latestHoldStart()
                    if (controller.start()) controller.lock()
                }
            },
            modifier =
                modifier
                    .testTag("composer.voice.launcher")
                    .width(slotWidth)
                    .height(48.dp)
                    .semantics { contentDescription = label }
                    .onGloballyPositioned { coordinates ->
                        // Cancel lies across the composer from the microphone, in either layout direction.
                        val center = coordinates.positionInRoot().x + coordinates.size.width / 2f
                        val rootWidth = coordinates.findRootCoordinates().size.width
                        controller.hold.aimCancel(if (center > rootWidth / 2f) -1f else 1f)
                    }.pointerInput(controller) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!latestCanStart()) return@awaitEachGesture
                            latestHoldStart()
                            val started = controller.start()
                            if (!started) return@awaitEachGesture
                            // Consume the down so the FAB's internal clickable
                            // doesn't ALSO interpret this press as a tap and fire
                            // its accessibility onClick after our hold gesture
                            // already handled stop/review/cancel.
                            down.consume()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            var displacement = Offset.Zero
                            var moved = false
                            var canceled = false
                            var locked = false
                            var terminated = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || (!change.pressed && change.isConsumed)) break
                                    change.consume()
                                    displacement += change.position - change.previousPosition
                                    val deltaX = displacement.x
                                    val deltaY = displacement.y
                                    moved = moved || displacement.getDistance() > viewConfiguration.touchSlop
                                    controller.updateDrag(deltaX, deltaY, cancelThresholdPx, lockThresholdPx)
                                    val towardCancel = controller.hold.cancelTravel(deltaX)
                                    if (!locked && -deltaY > lockThresholdPx && towardCancel <= cancelThresholdPx) {
                                        locked = true
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.Confirm,
                                        )
                                        controller.lock()
                                        terminated = true
                                        return@awaitEachGesture
                                    }
                                    if (!canceled && towardCancel > cancelThresholdPx) {
                                        canceled = true
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.Reject,
                                        )
                                    } else if (canceled && towardCancel <= cancelThresholdPx) {
                                        canceled = false
                                    }
                                    if (!change.pressed) {
                                        val duration = change.uptimeMillis - down.uptimeMillis
                                        val heldLongEnough = duration >= viewConfiguration.longPressTimeoutMillis
                                        when {
                                            canceled -> controller.cancel()
                                            heldLongEnough -> controller.stopHeld()
                                            moved -> controller.stop()
                                            else -> controller.lock()
                                        }
                                        terminated = true
                                        break
                                    }
                                }
                            } finally {
                                // Composable removal / coroutine cancellation while still
                                // recording-unlocked (leaving the chat mid-hold) keeps the
                                // take as this chat's draft instead of orphaning the recorder.
                                if (!terminated && controller.isRecording && !controller.locked) {
                                    controller.interrupt()
                                }
                            }
                        }
                    },
        ) {
            if (recording) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                MiniWaveformGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The prototype's five-bar voice glyph: 2dp bars of 6/12/18/12/6dp, 2dp apart, inside a 24dp box. */
@Composable
@Suppress("FunctionNaming")
internal fun MiniWaveformGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val heights = listOf(6.dp, 12.dp, 18.dp, 12.dp, 6.dp)
    Row(
        modifier = modifier.size(24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEach { height ->
            Box(
                Modifier
                    .width(2.dp)
                    .height(height)
                    .background(color, CircleShape),
            )
        }
    }
}

internal fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
