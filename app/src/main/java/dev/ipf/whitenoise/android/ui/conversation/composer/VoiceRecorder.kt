package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/**
 * Hold-to-record voice button. Press → start; release inside the button
 * bounds → stop and send. Drag the finger outside the button before
 * releasing → cancel. The cancel threshold is `cancelThresholdPx` away
 * from the down position; the gesture stays as a pointerInput input so
 * Compose doesn't fight us for the up event.
 */
@Composable
internal fun MicHoldButton(controller: dev.ipf.whitenoise.android.audio.VoiceRecordingController) {
    val haptics = LocalHapticFeedback.current
    val cancelThresholdDp = 120.dp
    val lockThresholdDp = 80.dp
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { cancelThresholdDp.toPx() }
    val lockThresholdPx = with(density) { lockThresholdDp.toPx() }
    val recording = controller.isRecording
    FloatingActionButton(
        // Accessibility fallback: a tap (TalkBack double-tap, keyboard
        // Enter, switch access) toggles record-and-lock so users who can't
        // perform the press-and-hold gesture can still send voice notes.
        onClick = {
            if (controller.isRecording) {
                controller.stop()
            } else if (controller.start()) {
                controller.lock()
            }
        },
        modifier =
            Modifier
                .size(44.dp)
                .pointerInput(controller) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val started = controller.start()
                        if (!started) return@awaitEachGesture
                        // Consume the down so the FAB's internal clickable
                        // doesn't ALSO interpret this press as a tap and fire
                        // its accessibility onClick after our hold gesture
                        // already handled stop/send/cancel.
                        down.consume()
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        var canceled = false
                        var locked = false
                        var terminated = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    // Parent stole the pointer — cancel rather than orphan the recorder.
                                    controller.cancel()
                                    terminated = true
                                    break
                                }
                                change.consume()
                                val deltaX = change.position.x - down.position.x
                                val deltaY = change.position.y - down.position.y
                                controller.updateDrag(deltaX, deltaY, cancelThresholdPx, lockThresholdPx)
                                if (!locked && -deltaY > lockThresholdPx && -deltaX <= cancelThresholdPx) {
                                    locked = true
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                    controller.lock()
                                    terminated = true
                                    return@awaitEachGesture
                                }
                                if (!canceled && -deltaX > cancelThresholdPx) {
                                    canceled = true
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                } else if (canceled && -deltaX <= cancelThresholdPx) {
                                    canceled = false
                                }
                                if (change.changedToUp() || !change.pressed) {
                                    if (canceled) controller.cancel() else controller.stop()
                                    terminated = true
                                    break
                                }
                            }
                        } finally {
                            // Composable removal / coroutine cancellation while still
                            // recording-unlocked → cancel cleanly instead of letting
                            // the recorder tick to the MAX_RECORDING_MS auto-stop.
                            if (!terminated && controller.isRecording && !controller.locked) {
                                controller.cancel()
                            }
                        }
                    }
                },
        containerColor =
            if (recording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = stringResource(R.string.voice_message_record),
        )
    }
}

@Composable
internal fun RecordingStripLeading(
    controller: dev.ipf.whitenoise.android.audio.VoiceRecordingController,
    modifier: Modifier = Modifier,
) {
    val pulseScale by rememberInfiniteRecordingPulse()
    val canceling = controller.willCancel
    val locked = controller.locked
    val cancelTint = MaterialTheme.colorScheme.error

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
            tint = if (canceling) cancelTint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Box(
            modifier =
                Modifier
                    .size((10 * pulseScale).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
        )
        Text(
            formatRecordingDuration(controller.elapsedMs),
            style = MaterialTheme.typography.labelLarge,
            color = if (canceling) cancelTint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (locked) {
            // Locked: the user has handed off control. The hint copy
            // collapses to a compact "Locked" indicator so the row stays
            // visually quiet while the trailing Stop+Trash do the work.
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.voice_message_locked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.voice_message_release_to_send),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun LockHintAbove(
    controller: dev.ipf.whitenoise.android.audio.VoiceRecordingController,
    modifier: Modifier = Modifier,
) {
    if (controller.locked || !controller.isRecording) return
    val density = LocalDensity.current
    val rawDp = with(density) { (-controller.verticalOffsetPx).toDp() }
    val rise = rawDp.value.coerceIn(0f, 80f).dp
    val armed = controller.willLock
    Box(
        modifier =
            modifier
                .offset(y = -rise - 56.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (armed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint =
                if (armed) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun rememberInfiniteRecordingPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "rec-pulse")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "rec-pulse-scale",
    )
}

private fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
