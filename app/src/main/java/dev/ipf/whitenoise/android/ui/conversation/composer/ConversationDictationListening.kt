@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import kotlin.math.PI
import kotlin.math.sin

private const val DICTATION_LISTENING_BAR_COUNT = 3
private const val DICTATION_LISTENING_BAR_MIN_HEIGHT_DP = 6f
private const val DICTATION_LISTENING_BAR_HEIGHT_RANGE_DP = 8f
private const val DICTATION_PULSE_FADE_MILLIS = 120

/** The listening pulse fades in place; the commands keep their positions when capture stops. */
@Composable
internal fun ConversationDictationListeningSlot(visible: Boolean) {
    val motion = rememberSystemAnimationsEnabled()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(if (motion) tween(DICTATION_ACTIONS_RESIZE_MILLIS) else snap()),
        exit = fadeOut(if (motion) tween(DICTATION_PULSE_FADE_MILLIS) else snap()),
    ) {
        ConversationDictationListeningIndicator()
    }
}

/** Three animated bars distinguish live capture from circular action processing. */
@Composable
private fun ConversationDictationListeningIndicator() {
    val transition = rememberInfiniteTransition(label = "dictation listening")
    val phase by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "dictation listening phase",
        )
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier =
            Modifier
                .size(24.dp)
                .testTag(DICTATION_LISTENING_INDICATOR_TAG),
    ) {
        val barWidth = 3.dp.toPx()
        val barSpacing = 2.dp.toPx()
        val totalWidth =
            barWidth * DICTATION_LISTENING_BAR_COUNT +
                barSpacing * (DICTATION_LISTENING_BAR_COUNT - 1)
        val firstBarLeft = (size.width - totalWidth) / 2f
        repeat(DICTATION_LISTENING_BAR_COUNT) { index ->
            val phaseOffset = index / DICTATION_LISTENING_BAR_COUNT.toFloat()
            val level = ((sin((phase + phaseOffset) * 2f * PI) + 1f) / 2f).toFloat()
            val barHeight =
                (
                    DICTATION_LISTENING_BAR_MIN_HEIGHT_DP +
                        DICTATION_LISTENING_BAR_HEIGHT_RANGE_DP * level
                ).dp.toPx()
            drawRoundRect(
                color = barColor,
                topLeft =
                    Offset(
                        x = firstBarLeft + index * (barWidth + barSpacing),
                        y = (size.height - barHeight) / 2f,
                    ),
                size = Size(width = barWidth, height = barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
