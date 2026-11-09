package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

private const val MEDIA_FALLBACK_CLOSE_SCALE = 0.92f

/**
 * When nothing can travel back to the timeline (zoomed, another page, thumbnail scrolled away), the
 * viewer still leaves the way it arrived: it fades and settles back instead of vanishing.
 */
internal suspend fun closeWithoutTravel(
    fallbackProgress: Animatable<Float, AnimationVector1D>,
    motionEnabled: Boolean,
    fallbackAllowed: Boolean,
    onClosed: () -> Unit,
) {
    if (motionEnabled && fallbackAllowed) {
        fallbackProgress.animateTo(0f, tween(MEDIA_CLOSE_MILLIS, easing = FastOutSlowInEasing))
    }
    onClosed()
}

internal fun Modifier.mediaViewerFallbackClose(fraction: () -> Float): Modifier =
    graphicsLayer {
        val value = fraction()
        if (value < 1f) {
            alpha = value
            val scale = MEDIA_FALLBACK_CLOSE_SCALE + (1f - MEDIA_FALLBACK_CLOSE_SCALE) * value
            scaleX = scale
            scaleY = scale
        }
    }
