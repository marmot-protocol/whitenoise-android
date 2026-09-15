package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Test tag for the reply glyph revealed behind a swiped bubble. */
internal fun messageReplySwipeGlyphTestTag(messageIdHex: String): String = "message-reply-swipe-glyph:$messageIdHex"

/**
 * Drag bookkeeping for swipe-to-reply on one bubble. It owns the raw drag
 * distance, the armed flag, the arming pulse and the sampled bounds the glyph
 * is positioned from, so the bubble composable only has to feed it deltas.
 */
@Stable
internal class MessageReplySwipeState(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val thresholdPx: Float,
    private val maximumPx: Float,
) {
    private var rawDistance by mutableFloatStateOf(0f)
    private var armed by mutableStateOf(false)
    private var settleJob: Job? = null
    private var pulseJob: Job? = null

    /** One-shot scale bump played the moment the drag arms the reply. */
    val pulse = Animatable(1f)

    /** Row bounds in root, sampled only at rest so a drag cannot chase itself. */
    var rowBoundsInRoot by mutableStateOf<Rect?>(null)

    /** Bubble bounds in root, sampled only at rest for the same reason. */
    var bubbleBoundsInRoot by mutableStateOf<Rect?>(null)

    /** True while nothing is dragged, which is when bounds may be resampled. */
    val atRest: Boolean get() = rawDistance == 0f

    /** Distance the bubble actually travels, rubber-banded past the threshold. */
    val displayedDistance: Float get() = resistedReplySwipeDistance(rawDistance, thresholdPx, maximumPx)

    /** Glyph reveal progress, saturating at 1 once the reply is armed. */
    val progress: Float get() = replySwipeProgress(displayedDistance, thresholdPx)

    /**
     * Records a new raw drag [distance]. Crossing the threshold upward arms the
     * reply once, with a haptic tick and the arming pulse; falling back below it
     * disarms silently and snaps the pulse back to rest.
     */
    fun dragTo(distance: Float) {
        settleJob?.cancel()
        settleJob = null
        rawDistance = distance.coerceAtLeast(0f)
        val nowArmed = rawDistance >= thresholdPx
        if (nowArmed == armed) return
        armed = nowArmed
        if (nowArmed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        // The pulse runs on its own job so a later drag delta cannot cancel it
        // halfway and strand the glyph at an intermediate scale.
        pulseJob?.cancel()
        pulseJob = scope.launch { if (nowArmed) playArmingPulse() else restPulse() }
    }

    /** Fires [onReply] when the drag ended armed, then springs the bubble home. */
    fun release(onReply: () -> Unit) {
        val shouldReply = armed
        armed = false
        if (shouldReply) onReply()
        settle()
    }

    /** Abandons the drag without replying and springs the bubble home. */
    fun cancel() {
        armed = false
        settle()
    }

    private fun settle() {
        pulseJob?.cancel()
        settleJob?.cancel()
        settleJob = scope.launch { springBack() }
    }

    private suspend fun playArmingPulse() {
        pulse.snapTo(1f)
        pulse.animateTo(
            targetValue = MessageReplySwipeMetrics.ICON_PULSE_SCALE,
            animationSpec = tween(durationMillis = MessageReplySwipeMetrics.PULSE_DURATION_MILLIS),
        )
        pulse.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = MessageReplySwipeMetrics.PULSE_DURATION_MILLIS),
        )
    }

    private suspend fun restPulse() {
        pulse.snapTo(1f)
    }

    private suspend fun springBack() {
        val start = rawDistance
        if (start > 0f) {
            Animatable(start).animateTo(
                targetValue = 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ) { rawDistance = value.coerceAtLeast(0f) }
        }
        rawDistance = 0f
        restPulse()
    }
}

/** Remembers one [MessageReplySwipeState] per message, resolving the thresholds in pixels. */
@Composable
internal fun rememberMessageReplySwipeState(messageIdHex: String): MessageReplySwipeState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val thresholdPx = with(density) { MessageReplySwipeMetrics.Threshold.toPx() }
    val maximumPx = with(density) { MessageReplySwipeMetrics.Maximum.toPx() }
    return remember(messageIdHex, thresholdPx, maximumPx) {
        MessageReplySwipeState(
            scope = scope,
            haptics = haptics,
            thresholdPx = thresholdPx,
            maximumPx = maximumPx,
        )
    }
}

/**
 * Draws the reply arrow the swiped bubble uncovers. It sits at the bubble's
 * leading edge, vertically centred on the bubble, fading and growing with the
 * drag and bumping once when the reply arms.
 */
@Composable
@Suppress("FunctionNaming") // Compose UI entry points use PascalCase.
internal fun BoxScope.MessageReplySwipeGlyph(
    state: MessageReplySwipeState,
    messageIdHex: String,
) {
    val rowBounds = state.rowBoundsInRoot
    val bubbleBounds = state.bubbleBoundsInRoot
    val progress = state.progress
    if (rowBounds == null || bubbleBounds == null || state.displayedDistance <= 0f) return
    val layoutDirection = LocalLayoutDirection.current
    val directionMultiplier = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    val density = LocalDensity.current
    val travelPx = with(density) { MessageReplySwipeMetrics.IconTravel.toPx() }
    val targetSizePx = with(density) { MessageReplySwipeMetrics.IconTargetSize.toPx() }
    val baseScale = replySwipeIconScale(progress)
    val leadingOffset =
        if (layoutDirection == LayoutDirection.Ltr) {
            bubbleBounds.left - rowBounds.left
        } else {
            rowBounds.right - bubbleBounds.right
        }
    val topOffset = bubbleBounds.center.y - rowBounds.top - (targetSizePx / 2f)
    Box(
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(leadingOffset.roundToInt(), topOffset.roundToInt()) }
                .size(MessageReplySwipeMetrics.IconTargetSize)
                .graphicsLayer {
                    alpha = replySwipeIconAlpha(progress)
                    translationX = travelPx * progress * directionMultiplier
                    scaleX = baseScale * state.pulse.value
                    scaleY = baseScale * state.pulse.value
                }.testTag(messageReplySwipeGlyphTestTag(messageIdHex)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_reply_swipe),
            contentDescription = null,
            modifier = Modifier.size(MessageReplySwipeMetrics.IconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
