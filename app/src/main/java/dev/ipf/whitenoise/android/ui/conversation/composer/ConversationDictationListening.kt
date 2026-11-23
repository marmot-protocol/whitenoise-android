@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import kotlin.math.PI
import kotlin.math.sin

private const val DICTATION_LISTENING_BAR_COUNT = 3
private const val DICTATION_LISTENING_BAR_MIN_HEIGHT_DP = 6f
private const val DICTATION_LISTENING_BAR_HEIGHT_RANGE_DP = 8f
private const val DICTATION_PULSE_FADE_MILLIS = 120

/**
 * The one place that names the continue command's glyph, so it can be swapped without touching
 * the layout that hosts it.
 */
@DrawableRes
internal val DICTATION_CONTINUE_ICON = R.drawable.ic_mic
internal const val DICTATION_CONTINUE_TAG = "dictation-continue"
internal const val DICTATION_PREVIOUS_PENDING_TAG = "dictation-previous-pending"

/**
 * While a follow-up records and the previous dictation is still being transcribed, a thin ring
 * around the pulse shows that work is pending.
 */
@Composable
internal fun ConversationDictationListeningSlot(
    visible: Boolean,
    previousPending: Boolean,
    resize: Boolean,
) {
    DictationLeadingSlot(visible, resize = resize) {
        Box(contentAlignment = Alignment.Center) {
            ConversationDictationListeningIndicator()
            if (previousPending) {
                val pendingLabel = stringResource(R.string.dictation_processing)
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .size(DICTATION_LISTENING_PULSE_WIDTH)
                            .testTag(DICTATION_PREVIOUS_PENDING_TAG)
                            .semantics { contentDescription = pendingLabel },
                    strokeWidth = 1.5.dp,
                )
            }
        }
    }
}

/**
 * Starts the next recording from the same slot as the listening pulse, while the previous dictation is
 * still being transcribed. It is icon-only; its label and long-press explanation carry the words.
 */
@Composable
internal fun ConversationDictationContinueSlot(
    visible: Boolean,
    onContinue: () -> Unit,
) {
    DictationLeadingSlot(visible) {
        IconButton(
            onClick = onContinue,
            enabled = visible,
            modifier =
                Modifier
                    .width(DICTATION_LISTENING_PULSE_WIDTH)
                    .height(ComposerShortcutTarget)
                    .testTag(DICTATION_CONTINUE_TAG)
                    .explainOnLongPress(
                        composerExplanation(
                            R.string.dictation_continue_recording,
                            R.string.explanation_conversation_dictation_continue,
                        ),
                    ),
        ) {
            Icon(
                painter = painterResource(DICTATION_CONTINUE_ICON),
                contentDescription = stringResource(R.string.dictation_continue_recording),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DictationLeadingSlot(
    visible: Boolean,
    resize: Boolean = false,
    content: @Composable () -> Unit,
) {
    val motion = rememberSystemAnimationsEnabled()
    val resizeSpec = if (motion) tween<IntSize>(DICTATION_ACTIONS_RESIZE_MILLIS) else snap()
    val expand = if (resize) expandHorizontally(resizeSpec, expandFrom = Alignment.Start) else EnterTransition.None
    val shrink = if (resize) shrinkHorizontally(resizeSpec, shrinkTowards = Alignment.Start) else ExitTransition.None
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(if (motion) tween(DICTATION_ACTIONS_RESIZE_MILLIS) else snap()) + expand,
        exit = fadeOut(if (motion) tween(DICTATION_PULSE_FADE_MILLIS) else snap()) + shrink,
    ) {
        content()
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
                .size(DICTATION_LISTENING_PULSE_WIDTH)
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
