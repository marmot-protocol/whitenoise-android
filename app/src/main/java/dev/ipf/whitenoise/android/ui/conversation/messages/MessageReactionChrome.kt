@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionSummaryChip
import kotlin.math.roundToInt

internal val MessageBubbleBottomAlignmentLine = HorizontalAlignmentLine { old, new -> minOf(old, new) }

@Composable
internal fun RowScope.MessageSenderAvatarSlot(
    showSenderAvatar: Boolean,
    title: String,
    seed: String,
    pictureUrl: String?,
    enabled: Boolean,
    alignToBubbleBottom: Boolean,
    onClick: () -> Unit,
) {
    val avatarAlignment =
        if (alignToBubbleBottom) {
            Modifier.alignBy { it.measuredHeight }
        } else {
            Modifier.align(Alignment.Bottom)
        }
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .then(avatarAlignment),
    ) {
        if (showSenderAvatar) {
            Box(
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .clickable(enabled = enabled, onClick = onClick),
            ) {
                Avatar(
                    title = title,
                    seed = seed,
                    size = 32.dp,
                    pictureUrl = pictureUrl,
                )
            }
        }
    }
    Spacer(Modifier.width(8.dp))
}

@Composable
internal fun ColumnScope.MessageReactionSummary(
    tallies: List<ReactionTally>,
    mine: Boolean,
    bubbleBorderOverrideArgb: Long? = null,
    visibilityState: MutableTransitionState<Boolean>? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val reactionChipPadding = reactionChipPadding(mine)
    val targetVisible = tallies.isNotEmpty()
    val defaultVisibilityState = remember { MutableTransitionState(targetVisible) }
    val resolvedVisibilityState = visibilityState ?: defaultVisibilityState
    resolvedVisibilityState.targetState = targetVisible
    val transition = rememberTransition(resolvedVisibilityState, label = "messageReactionHost")
    var lastTallies by remember { mutableStateOf(tallies) }
    if (targetVisible) {
        SideEffect { lastTallies = tallies }
    }
    val displayTallies = if (targetVisible) tallies else lastTallies
    val hostAlpha by
        transition.animateFloat(
            transitionSpec = { tween(durationMillis = REACTION_HOST_FADE_DURATION_MILLIS) },
            label = "messageReactionHostAlpha",
        ) { if (it) 1f else 0f }
    val hostScale by
        transition.animateFloat(
            transitionSpec = { tween(durationMillis = REACTION_HOST_SCALE_DURATION_MILLIS) },
            label = "messageReactionHostScale",
        ) { if (it) 1f else 0.92f }
    val hostSizeFraction by
        transition.animateFloat(
            transitionSpec = { tween(durationMillis = REACTION_HOST_SIZE_DURATION_MILLIS) },
            label = "messageReactionHostSize",
        ) { if (it) 1f else 0f }
    val hostAnimating = transition.currentState != transition.targetState || transition.isRunning
    val hostGraphicsModifier =
        if (hostAnimating) {
            Modifier.graphicsLayer {
                alpha = hostAlpha
                scaleX = hostScale
                scaleY = hostScale
            }
        } else {
            Modifier
        }
    val hostClipModifier = if (hostAnimating) Modifier.clipToBounds() else Modifier
    Box(
        modifier =
            reactionHostModifier(
                mine = mine,
                padding = reactionChipPadding,
                clipModifier = hostClipModifier,
                sizeFraction = hostSizeFraction,
            ),
    ) {
        if (targetVisible || transition.currentState || transition.isRunning) {
            ReactionSummaryChip(
                tallies = displayTallies,
                outgoing = mine,
                customAmoledBorderColor = bubbleBorderOverrideArgb?.let(::colorFromArgb),
                enabled = enabled,
                onClick = onClick,
                modifier = hostGraphicsModifier,
            )
        }
    }
}

private fun reactionChipPadding(mine: Boolean): PaddingValues =
    if (mine) {
        PaddingValues(start = 10.dp)
    } else {
        PaddingValues(end = 10.dp)
    }

// Keep the chip tucked onto the bubble's lower outer edge while its
// reported height expands or contracts with the visibility transition.
private fun ColumnScope.reactionHostModifier(
    mine: Boolean,
    padding: PaddingValues,
    clipModifier: Modifier,
    sizeFraction: Float,
): Modifier =
    Modifier
        .align(if (mine) Alignment.Start else Alignment.End)
        .padding(padding)
        .then(clipModifier)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val overlap = 6.dp.roundToPx()
            val expandedHeight = (placeable.height - overlap).coerceAtLeast(0)
            val height = (expandedHeight * sizeFraction).roundToInt()
            layout(
                width = placeable.width,
                height = height,
                alignmentLines = mapOf(MessageBubbleBottomAlignmentLine to 0),
            ) {
                placeable.place(0, -overlap)
            }
        }

private const val REACTION_HOST_FADE_DURATION_MILLIS = 150
private const val REACTION_HOST_SCALE_DURATION_MILLIS = 200
private const val REACTION_HOST_SIZE_DURATION_MILLIS = 200
