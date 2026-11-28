package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_CLOSE_MILLIS
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled

/** Static fallback ring alpha when the system removes animations. */
private const val VOICE_STATIC_RING_ALPHA = 0.4f

/**
 * The voice flow's one tween: the app's open or close duration and curve, or a snap when the system removes
 * animations so every transition settles in its first frame.
 */
@Composable
internal fun <T> voiceTween(
    open: Boolean,
    easing: Easing = if (open) AppMotionOpenEasing else AppMotionCloseEasing,
): FiniteAnimationSpec<T> =
    if (rememberSystemAnimationsEnabled()) {
        tween(durationMillis = if (open) APP_MOTION_OPEN_MILLIS else APP_MOTION_CLOSE_MILLIS, easing = easing)
    } else {
        snap()
    }

/**
 * The indeterminate ring that marks a voice note that is sending or closing. Under "Remove animations" it is a
 * still ring, because an indeterminate indicator parked on one frame would read as a stuck fraction.
 */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceSendingIndicator(
    size: Dp,
    strokeWidth: Dp,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    if (rememberSystemAnimationsEnabled()) {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            color = color,
            strokeWidth = strokeWidth,
        )
    } else {
        Canvas(modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            drawCircle(
                color = color.copy(alpha = color.alpha * VOICE_STATIC_RING_ALPHA),
                radius = (this.size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke),
            )
        }
    }
}

/** Rise of content that fades in, and of the lock pill as it appears. */
internal val VoiceEnterRise = 8.dp

/**
 * Runs once per call site from 0 to 1 on the open tween. It starts at 1 when the system removes animations, so the
 * first frame is already the settled one.
 */
@Composable
internal fun rememberVoiceEnterProgress(): State<Float> {
    val animate = rememberSystemAnimationsEnabled()
    val spec = voiceTween<Float>(open = true)
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(progress) { progress.animateTo(1f, spec) }
    return progress.asState()
}

/** Fades content in while it rises [rise] into place; [progress] is read in the draw layer only. */
internal fun Modifier.voiceEnterLayer(
    rise: Dp = VoiceEnterRise,
    progress: () -> Float,
): Modifier =
    graphicsLayer {
        val value = progress()
        alpha = value
        translationY = (1f - value) * rise.toPx()
    }
