package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.Radii
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** How far the strip slides as it arrives from the microphone or leaves toward the cancel side. */
private const val HALF_TURN_DEGREES = 180f
private val VoiceStripSlide = 12.dp
private val VoiceStatusDotSize = 12.dp

/** The armed trash grows by this factor so the cancel target reads before release. */
private const val VOICE_TRASH_ARMED_SCALE = 1.2f

/** The hint and lock glyph use this alpha so the timer stays the strongest element. */
private const val VOICE_HINT_ALPHA = 0.7f

/**
 * The recording strip drawn over the composer pill: status on the leading side, the gesture hint trailing.
 * Its content follows the finger toward the cancel side; [exit] (0 to 1) slides and fades it out after a cancel.
 */
@Composable
@Suppress("FunctionNaming")
internal fun RecordingStripLeading(
    controller: VoiceRecordingController,
    modifier: Modifier = Modifier,
    exit: () -> Float = { 0f },
) {
    val enter by rememberVoiceEnterProgress()
    val micSide = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val settle = rememberStripSettle(controller)
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    val leaving = exit()
                    alpha = enter * (1f - leaving)
                    translationX =
                        ((1f - enter) * micSide + leaving * controller.hold.cancelDirection) * VoiceStripSlide.toPx()
                }.clip(RoundedCornerShape(Radii.xl))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = Dimens.spaceMd, vertical = Dimens.spaceSm),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = if (controller.isRecording) controller.hold.dragOffsetPx else settle.value
                    },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            RecordingStripStatus(controller)
            RecordingStripMiddle()
            RecordingStripTrailing(controller)
        }
    }
}

/**
 * After an ordinary release the content eases back from wherever the finger left it; after a cancel it stays put so
 * the exit continues from there.
 */
@Composable
private fun rememberStripSettle(controller: VoiceRecordingController): State<Float> {
    val settle = remember(controller) { Animatable(0f) }
    val spec = voiceTween<Float>(open = false, easing = AppMotionOpenEasing)
    LaunchedEffect(controller, controller.isRecording) {
        if (!controller.isRecording) {
            settle.snapTo(controller.hold.lastDragOffsetPx)
            if (controller.isClosing) settle.animateTo(0f, spec)
        }
    }
    return settle.asState()
}

/** The cancel glyph while a hold can still be cancelled, the recording dot, and the elapsed or remaining time. */
@Composable
@Suppress("FunctionNaming")
private fun RecordingStripStatus(controller: VoiceRecordingController) {
    val canceling = controller.hold.willCancel
    val cancelTint = MaterialTheme.colorScheme.error
    val nearLimit = controller.isNearLimit
    val remainingForDisplay = ((controller.remainingMs + 999L) / 1000L) * 1000L
    val timing = formatRecordingDuration(if (nearLimit) remainingForDisplay else controller.elapsedMs)
    val timerLabel = stringResource(R.string.voice_recording_stops_in, timing)
    // A locked take has a real trash button on the trailing row, so the strip's decorative one leaves.
    if (controller.isRecording && !controller.locked && !controller.isPaused) RecordingStripTrash(canceling)
    RecordingStripDot(controller)
    Text(
        timing,
        style = MaterialTheme.typography.labelLarge,
        color = if (canceling || nearLimit) cancelTint else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (nearLimit) Modifier.clearAndSetSemantics { contentDescription = timerLabel } else Modifier,
    )
}

/** The cancel target: it grows and turns to the error colour once release would discard the take. */
@Composable
@Suppress("FunctionNaming")
private fun RecordingStripTrash(armed: Boolean) {
    val animate = rememberSystemAnimationsEnabled()
    val tint by animateColorAsState(
        targetValue = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = voiceTween(open = false, easing = AppMotionOpenEasing),
        label = "voice-trash-tint",
    )
    val scale by animateFloatAsState(
        targetValue = if (armed && animate) VOICE_TRASH_ARMED_SCALE else 1f,
        animationSpec = voiceTween(open = false, easing = AppMotionOpenEasing),
        label = "voice-trash-scale",
    )
    Icon(
        painterResource(R.drawable.ic_delete),
        contentDescription = null,
        tint = tint,
        modifier =
            Modifier
                .size(24.dp)
                .testTag("composer.voice.trash")
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
    )
}

/** Pulses while capturing, rests grey while paused and becomes a small ring while the encoder closes the take. */
@Composable
@Suppress("FunctionNaming")
private fun RecordingStripDot(controller: VoiceRecordingController) {
    val pulseScale by rememberInfiniteRecordingPulse()
    val paused = controller.isPaused
    val closing = !controller.isRecording && controller.isClosing
    val dotColor by animateColorAsState(
        targetValue = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        animationSpec = voiceTween(open = false),
        label = "voice-dot-color",
    )
    Crossfade(targetState = closing, animationSpec = voiceTween(open = false), label = "voice-dot-closing") {
        Box(Modifier.size(VoiceStatusDotSize), contentAlignment = Alignment.Center) {
            if (it) {
                // The take is stopped and the encoder is closing it; review opens next.
                VoiceSendingIndicator(
                    size = VoiceStatusDotSize,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.testTag("composer.voice.closing"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(
                    Modifier
                        .size(VoiceStatusDotSize)
                        .graphicsLayer {
                            val scale = if (paused || !controller.isRecording) 1f else pulseScale
                            scaleX = scale
                            scaleY = scale
                        }.clip(CircleShape)
                        .background(dotColor),
                )
            }
        }
    }
}

/** The space between the timer and the trailing controls. */
@Composable
@Suppress("FunctionNaming")
private fun RowScope.RecordingStripMiddle() {
    Spacer(Modifier.weight(1f))
}

/** What the trailing side of the strip offers in each recording state. */
private enum class StripTrailing { Hint, Controls, None }

/** The release hint while holding, lock and Pause once locked, Resume while paused, and nothing while closing. */
@Composable
@Suppress("FunctionNaming")
private fun RecordingStripTrailing(controller: VoiceRecordingController) {
    val nearLimit = controller.isNearLimit
    val mode =
        when {
            !controller.isRecording -> StripTrailing.None
            controller.isPaused || controller.locked -> StripTrailing.Controls
            nearLimit -> StripTrailing.None
            else -> StripTrailing.Hint
        }
    Crossfade(targetState = mode, animationSpec = voiceTween(open = false), label = "voice-strip-trailing") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            when (it) {
                StripTrailing.Hint -> RecordingStripHint(controller)
                StripTrailing.Controls ->
                    RecordingStripLockedControls(controller, showLock = controller.locked && !nearLimit)
                StripTrailing.None -> Unit
            }
        }
    }
}

/** The cancel hint fades as the finger slides toward the cancel threshold. */
@Composable
@Suppress("FunctionNaming")
private fun RecordingStripHint(controller: VoiceRecordingController) {
    val hint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = VOICE_HINT_ALPHA)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        modifier = Modifier.graphicsLayer { alpha = 1f - controller.hold.cancelProgress },
    ) {
        Icon(
            painterResource(R.drawable.ic_arrow_back),
            contentDescription = null,
            tint = hint,
            modifier =
                Modifier.size(16.dp).graphicsLayer {
                    // The arrow mirrors with the layout; turn it when the cancel side is the other one.
                    val pointsLeft = !rtl
                    val cancelLeft = controller.hold.cancelDirection < 0f
                    rotationZ = if (pointsLeft == cancelLeft) 0f else HALF_TURN_DEGREES
                },
        )
        Text(
            stringResource(R.string.voice_message_slide_to_cancel),
            style = MaterialTheme.typography.labelSmall,
            color = hint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A locked take: a quiet lock glyph and the Pause control. Stop and trash sit on the trailing row. */
@Composable
@Suppress("FunctionNaming")
private fun RecordingStripLockedControls(
    controller: VoiceRecordingController,
    showLock: Boolean,
) {
    if (showLock) {
        Icon(
            painterResource(R.drawable.ic_lock),
            contentDescription = stringResource(R.string.voice_message_locked),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
    val paused = controller.isPaused
    IconButton(
        onClick = { if (paused) controller.resume() else controller.pauseCapture() },
        modifier = Modifier.testTag(if (paused) "composer.voice.resume" else "composer.voice.pause"),
    ) {
        Icon(
            painterResource(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause),
            contentDescription =
                stringResource(if (paused) R.string.voice_recording_resume else R.string.voice_recording_pause),
            tint = if (paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun rememberInfiniteRecordingPulse(): State<Float> {
    // With animations off the dot stays at VoiceStatusDotSize, the unscaled size.
    if (!rememberSystemAnimationsEnabled()) return remember { mutableFloatStateOf(1f) }
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
