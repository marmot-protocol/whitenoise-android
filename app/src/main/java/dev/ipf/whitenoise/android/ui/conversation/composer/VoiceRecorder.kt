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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot

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
                        runVoiceRecordingDragGesture(
                            controller = controller,
                            down = down,
                            cancelThresholdPx = cancelThresholdPx,
                            lockThresholdPx = lockThresholdPx,
                            haptics = haptics,
                        )
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

/** One composer microphone slot: tap starts dictation, while press-and-hold records a voice note. */
@Suppress("FunctionNaming")
@Composable
internal fun ComposerMicrophoneButton(
    onDictation: () -> Unit,
    voiceRecordingController: dev.ipf.whitenoise.android.audio.VoiceRecordingController? = null,
    emphasized: Boolean = true,
) {
    val latestOnDictation by rememberUpdatedState(onDictation)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { 120.dp.toPx() }
    val lockThresholdPx = with(density) { 80.dp.toPx() }
    val voiceMessageLabel = stringResource(R.string.voice_message_record)
    val gestureModifier =
        if (voiceRecordingController == null) {
            Modifier
        } else {
            Modifier
                .semantics {
                    onLongClick(label = voiceMessageLabel) {
                        if (voiceRecordingController.start()) {
                            voiceRecordingController.lock()
                            true
                        } else {
                            false
                        }
                    }
                }.pointerInput(voiceRecordingController) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        when (awaitComposerMicrophonePress(down)) {
                            ComposerMicrophonePress.Tap -> latestOnDictation()
                            ComposerMicrophonePress.Cancelled -> Unit
                            ComposerMicrophonePress.LongPress -> {
                                if (!voiceRecordingController.start()) return@awaitEachGesture
                                haptics.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                )
                                runVoiceRecordingDragGesture(
                                    controller = voiceRecordingController,
                                    down = down,
                                    cancelThresholdPx = cancelThresholdPx,
                                    lockThresholdPx = lockThresholdPx,
                                    haptics = haptics,
                                )
                            }
                        }
                    }
                }
        }
    val buttonModifier = Modifier.size(48.dp).then(gestureModifier)
    ComposerMicrophoneButtonContent(
        emphasized = emphasized,
        modifier = buttonModifier,
        onClick = { latestOnDictation() },
    )
}

/** Renders the microphone as a primary action or a quiet in-field affordance. */
@Suppress("FunctionNaming")
@Composable
private fun ComposerMicrophoneButtonContent(
    emphasized: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    if (emphasized) {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = stringResource(R.string.dictate_text),
            )
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = stringResource(R.string.dictate_text),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class ComposerMicrophonePress {
    Tap,
    LongPress,
    Cancelled,
}

/** Resolves the short-tap/long-press boundary before either microphone feature takes ownership. */
private suspend fun AwaitPointerEventScope.awaitComposerMicrophonePress(down: PointerInputChange) =
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var resolution: ComposerMicrophonePress? = null
        while (resolution == null) {
            val change =
                awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull ComposerMicrophonePress.Cancelled
            val deltaX = change.position.x - down.position.x
            val deltaY = change.position.y - down.position.y
            resolution =
                when {
                    hypot(deltaX, deltaY) > viewConfiguration.touchSlop -> ComposerMicrophonePress.Cancelled
                    change.changedToUp() || !change.pressed -> ComposerMicrophonePress.Tap
                    else -> null
                }
            change.consume()
        }
        checkNotNull(resolution)
    } ?: ComposerMicrophonePress.LongPress

private data class VoiceRecordingDragProgress(
    val canceling: Boolean = false,
    val complete: Boolean = false,
)

/** Completes the existing drag-to-cancel/drag-to-lock voice-note contract after capture starts. */
private suspend fun AwaitPointerEventScope.runVoiceRecordingDragGesture(
    controller: dev.ipf.whitenoise.android.audio.VoiceRecordingController,
    down: PointerInputChange,
    cancelThresholdPx: Float,
    lockThresholdPx: Float,
    haptics: HapticFeedback,
) {
    var progress = VoiceRecordingDragProgress()
    try {
        while (!progress.complete) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            if (change == null) {
                controller.cancel()
                progress = progress.copy(complete = true)
                continue
            }
            change.consume()
            progress =
                handleVoiceRecordingDragChange(
                    controller = controller,
                    change = change,
                    down = down,
                    cancelThresholdPx = cancelThresholdPx,
                    lockThresholdPx = lockThresholdPx,
                    wasCanceling = progress.canceling,
                    haptics = haptics,
                )
        }
    } finally {
        if (!progress.complete && controller.isRecording && !controller.locked) controller.cancel()
    }
}

/** Applies one pointer update and reports whether voice recording owns more input. */
private fun handleVoiceRecordingDragChange(
    controller: dev.ipf.whitenoise.android.audio.VoiceRecordingController,
    change: PointerInputChange,
    down: PointerInputChange,
    cancelThresholdPx: Float,
    lockThresholdPx: Float,
    wasCanceling: Boolean,
    haptics: HapticFeedback,
): VoiceRecordingDragProgress {
    val deltaX = change.position.x - down.position.x
    val deltaY = change.position.y - down.position.y
    controller.updateDrag(deltaX, deltaY, cancelThresholdPx, lockThresholdPx)
    val canceling = -deltaX > cancelThresholdPx
    if (canceling && !wasCanceling) {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    return when {
        -deltaY > lockThresholdPx && -deltaX <= cancelThresholdPx -> {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            controller.lock()
            VoiceRecordingDragProgress(complete = true)
        }
        change.changedToUp() || !change.pressed -> {
            if (canceling) controller.cancel() else controller.stop()
            VoiceRecordingDragProgress(canceling = canceling, complete = true)
        }
        else -> VoiceRecordingDragProgress(canceling = canceling)
    }
}

/** Renders the single microphone slot or its app-owned Done/Cancel replacement. */
@Suppress("FunctionNaming")
@Composable
internal fun ComposerMicrophoneControl(
    state: ConversationDictationState,
    activeController: ConversationDictationController?,
    showVoiceMicrophone: Boolean,
    voiceController: dev.ipf.whitenoise.android.audio.VoiceRecordingController?,
    dictationCanStart: Boolean,
    reserveDictationActions: Boolean,
    emphasized: Boolean,
    onDictation: (() -> Unit)?,
) {
    val reservesDictationActions =
        reserveDictationActions && (dictationCanStart || activeController != null)
    Box(
        modifier = if (reservesDictationActions) Modifier.width(96.dp) else Modifier,
        contentAlignment = Alignment.CenterEnd,
    ) {
        activeController?.let { controller ->
            ConversationDictationCompactActions(
                state = state,
                controller = controller,
                emphasized = emphasized,
            )
        }
        if (showVoiceMicrophone) {
            val recorder = checkNotNull(voiceController)
            Box(contentAlignment = Alignment.BottomCenter) {
                LockHintAbove(controller = recorder)
                if (dictationCanStart) {
                    ComposerMicrophoneButton(
                        onDictation = checkNotNull(onDictation),
                        voiceRecordingController = recorder,
                        emphasized = emphasized,
                    )
                } else {
                    MicHoldButton(controller = recorder)
                }
            }
        } else if (dictationCanStart) {
            ComposerMicrophoneButton(
                onDictation = checkNotNull(onDictation),
                emphasized = emphasized,
            )
        }
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
                    .size(10.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }.clip(CircleShape)
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
internal fun rememberInfiniteRecordingPulse(): State<Float> {
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

internal fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
