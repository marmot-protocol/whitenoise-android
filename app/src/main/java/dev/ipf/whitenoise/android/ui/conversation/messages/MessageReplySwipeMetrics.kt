package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ReplySwipe
import dev.ipf.whitenoise.android.core.ReplySwipeGesture

/**
 * Geometry and motion constants for the swipe-to-reply affordance. The bubble
 * follows the finger one-for-one up to [Threshold], which arms the reply, then
 * rubber-bands asymptotically toward [Maximum] so an overdrag reads as resistance
 * rather than as extra travel.
 */
internal object MessageReplySwipeMetrics {
    /** Drag distance that arms the reply and completes the glyph reveal. */
    val Threshold = 64.dp

    /** Hard ceiling the resisted distance approaches but never reaches. */
    val Maximum = 96.dp

    /** Extra horizontal drift the glyph picks up across a full reveal. */
    val IconTravel = 10.dp

    /** Touch-target box the glyph is centred inside. */
    val IconTargetSize = 48.dp

    /** Drawn size of the reply glyph itself. */
    val IconSize = 24.dp

    /** Progress below which the glyph stays fully transparent. */
    const val ICON_REVEAL_START = 0.05f

    /** Glyph scale once the drag has reached the threshold. */
    const val ICON_READY_SCALE = 1.2f

    /** Peak of the one-shot pulse played when the reply arms. */
    const val ICON_PULSE_SCALE = 1.5f

    /** Duration of each half of the arming pulse. */
    const val PULSE_DURATION_MILLIS = 100
}

/**
 * Maps a raw drag distance onto the distance the bubble actually travels. Up to
 * [threshold] the bubble tracks the finger exactly, beyond it the overdrag is
 * compressed so the bubble approaches [maximum] asymptotically without reaching it.
 */
internal fun resistedReplySwipeDistance(
    rawDistance: Float,
    threshold: Float,
    maximum: Float,
): Float {
    val distance = rawDistance.coerceAtLeast(0f)
    if (distance <= threshold) return distance
    val overdrag = distance - threshold
    return if (threshold <= 0f || maximum <= threshold) {
        maximum.coerceAtLeast(0f)
    } else {
        threshold + ((maximum - threshold) * overdrag / (overdrag + threshold))
    }
}

/** Reveal progress of the glyph, saturating at 1 once the reply is armed. */
internal fun replySwipeProgress(
    displayedDistance: Float,
    threshold: Float,
): Float =
    if (threshold <= 0f) {
        0f
    } else {
        (displayedDistance / threshold).coerceIn(0f, 1f)
    }

/**
 * Glyph opacity for a reveal [progress]. The first sliver of travel is held
 * fully transparent so an incidental horizontal nudge never flashes the arrow.
 */
internal fun replySwipeIconAlpha(progress: Float): Float {
    val revealed = progress >= MessageReplySwipeMetrics.ICON_REVEAL_START
    return if (revealed) progress else 0f
}

/** Glyph scale for a reveal [progress], before the arming pulse is applied. */
internal fun replySwipeIconScale(progress: Float): Float {
    val growth = MessageReplySwipeMetrics.ICON_READY_SCALE - 1f
    return 1f + (growth * progress)
}

/**
 * Unclamped forward drag distance for a gesture, or zero while the gesture is
 * still too vertical to read as a reply swipe. The ceiling is left to
 * [resistedReplySwipeDistance] so the rubber band sees the real overdrag.
 */
internal fun ReplySwipeGesture.forwardReplySwipeDistance(): Float =
    if (ReplySwipe.isMostlyHorizontalRightward(totalX = totalX, totalY = totalY)) {
        totalX.coerceAtLeast(0f)
    } else {
        0f
    }
