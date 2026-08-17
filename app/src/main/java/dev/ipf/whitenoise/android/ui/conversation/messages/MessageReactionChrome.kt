@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.messages

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionSummaryChip

@Composable
internal fun RowScope.MessageSenderAvatarSlot(
    showSenderAvatar: Boolean,
    title: String,
    seed: String,
    pictureUrl: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(32.dp)
                .align(Alignment.Top),
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
    onClick: () -> Unit,
) {
    val reactionChipPadding =
        if (mine) {
            PaddingValues(start = 10.dp)
        } else {
            PaddingValues(end = 10.dp)
        }
    // Keep the chip tucked onto the bubble's lower outer edge without
    // covering the final text line or outgoing status cluster.
    Box(
        modifier =
            Modifier
                .align(if (mine) Alignment.Start else Alignment.End)
                .padding(reactionChipPadding)
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val overlap = 6.dp.roundToPx()
                    val height = (placeable.height - overlap).coerceAtLeast(0)
                    layout(placeable.width, height) {
                        placeable.place(0, -overlap)
                    }
                },
    ) {
        ReactionSummaryChip(
            tallies = tallies,
            outgoing = mine,
            onClick = onClick,
        )
    }
}
