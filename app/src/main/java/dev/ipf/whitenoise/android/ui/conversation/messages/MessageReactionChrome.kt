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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillRow
import kotlin.math.roundToInt

internal val MessageBubbleBottomAlignmentLine = HorizontalAlignmentLine { old, new -> minOf(old, new) }

/** Sender avatar slot beside incoming bubbles, shown at the end of a sender run. */
@Composable
internal fun RowScope.MessageSenderAvatarSlot(
    showSenderAvatar: Boolean,
    title: String,
    seed: String,
    pictureUrl: String?,
    enabled: Boolean,
    alignToBubbleBottom: Boolean,
    onClick: () -> Unit,
    // Decoded bytes MarmotKit stores for the sender (0.10.1); when present the URL is not fetched.
    picture: ImageBitmap? = null,
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
                .size(30.dp)
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
                    size = 30.dp,
                    pictureUrl = pictureUrl.takeIf { picture == null },
                    picture = picture,
                )
            }
        }
    }
    Spacer(Modifier.width(6.dp))
}

/** Reaction summary chips under a bubble. */
@Composable
internal fun ColumnScope.MessageReactionSummary(
    tallies: List<ReactionTally>,
    mine: Boolean,
    visibilityState: MutableTransitionState<Boolean>? = null,
    enabled: Boolean = true,
    onToggle: (String) -> Unit = {},
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
            ReactionPillRow(
                tallies = displayTallies,
                enabled = enabled,
                onToggle = onToggle,
                onOverflow = onClick,
                onLongPress = onClick,
                modifier = hostGraphicsModifier,
            )
        }
    }
}

/** Edge inset of the reaction row for the bubble direction. */
private fun reactionChipPadding(mine: Boolean): PaddingValues =
    if (mine) {
        PaddingValues(start = REACTION_ROW_EDGE_INSET)
    } else {
        PaddingValues(end = REACTION_ROW_EDGE_INSET)
    }

// Keep the chip tucked onto the bubble's lower outer edge while its
// reported height expands or contracts with the visibility transition.

/** Modifier hosting the reaction row under the bubble. */
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
            val overlap = REACTION_ROW_OVERLAP.roundToPx()
            // Report the pills' visible protrusion, not the touch row's. The 48 dp row centres 23 dp
            // pills, so its lower half is empty slop; reporting it pushed the next row — and, for the
            // last message, the composer — away from the reacted bubble by more than the chips show.
            val visibleHeight = placeable.height - REACTION_ROW_TRAILING_SLOP.roundToPx()
            val expandedHeight = (visibleHeight - overlap).coerceAtLeast(0)
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

// The prototype's metadata row: 12dp in from the bubble edge, and its 48dp touch row pulled up so the 23dp pills
// overlap the bubble's bottom edge by 9dp ((48 - 23) / 2 + 9).
private val REACTION_ROW_EDGE_INSET = 12.dp
private val REACTION_ROW_OVERLAP = 21.dp

// The 48dp touch row is centred on 23dp pills, so (48 - 23) / 2 of it is empty below them.
private val REACTION_PILL_HEIGHT = 23.dp
private val REACTION_TOUCH_ROW_HEIGHT = 48.dp
private val REACTION_ROW_TRAILING_SLOP = (REACTION_TOUCH_ROW_HEIGHT - REACTION_PILL_HEIGHT) / 2
private const val REACTION_HOST_SCALE_DURATION_MILLIS = 200
private const val REACTION_HOST_SIZE_DURATION_MILLIS = 200
