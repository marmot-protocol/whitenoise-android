package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ReactionParticipant
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.design.BottomAnchoredPopupPositionProvider
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** Attaches the reaction summary to the bubble's outer edge. */
internal fun Modifier.reactionSummaryAttachment(outgoing: Boolean): Modifier =
    padding(
        if (outgoing) {
            PaddingValues(end = REACTION_BUBBLE_EDGE_INSET)
        } else {
            PaddingValues(start = REACTION_BUBBLE_EDGE_INSET)
        },
    ).layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val overlap = REACTION_BUBBLE_OVERLAP.roundToPx()
        val height = (placeable.height - overlap).coerceAtLeast(0)
        layout(placeable.width, height) {
            placeable.place(0, -overlap)
        }
    }

/** Shows every reactor, initially filtered to the tapped emoji when one was selected. */
@Composable
internal fun ReactionDetailsSheet(
    participants: List<ReactionParticipant>,
    appState: WhiteNoiseAppState,
    initialEmoji: String? = null,
    onRemoveOwnReaction: ((String) -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    var selectedEmoji by
        remember(participants, initialEmoji) {
            mutableStateOf(initialEmoji?.takeIf { emoji -> participants.any { it.emoji == emoji } })
        }
    val activeAccountId = appState.activeAccount?.accountIdHex
    val emojiCounts =
        remember(participants) {
            participants
                .groupingBy { it.emoji }
                .eachCount()
                .toList()
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        }
    val visibleParticipants =
        remember(participants, selectedEmoji) {
            selectedEmoji?.let { emoji -> participants.filter { it.emoji == emoji } } ?: participants
        }

    KeyboardSafePopup(
        expanded = true,
        onDismissRequest = onDismissRequest,
        popupPositionProvider = BottomAnchoredPopupPositionProvider,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = amoledSheetContainerColor(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedEmoji == null,
                        onClick = { selectedEmoji = null },
                        label = { Text("${stringResource(R.string.reaction_filter_all)} · ${participants.size}") },
                    )
                    emojiCounts.forEach { (emoji, count) ->
                        FilterChip(
                            selected = selectedEmoji == emoji,
                            onClick = { selectedEmoji = emoji },
                            label = { Text("$emoji $count") },
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(
                        visibleParticipants,
                        key = { _, participant -> "${participant.sender}:${participant.emoji}:${participant.reactedAt}" },
                    ) { _, participant ->
                        val isMine = activeAccountId != null && participant.sender.equals(activeAccountId, ignoreCase = true)
                        ReactionParticipantRow(
                            participant = participant,
                            appState = appState,
                            mine = isMine,
                            onRemove = if (isMine && onRemoveOwnReaction != null) ({ onRemoveOwnReaction(participant.emoji) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionParticipantRow(
    participant: ReactionParticipant,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    if (mine && onRemove != null) onRemove() else appState.presentProfile(appState.npub(participant.sender))
                }.padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            title = appState.displayName(participant.sender),
            seed = participant.sender,
            size = 44.dp,
            pictureUrl = appState.avatarUrl(participant.sender),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = if (mine) stringResource(R.string.you) else appState.displayName(participant.sender),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mine) {
                Text(
                    text = stringResource(R.string.reaction_tap_to_remove),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = participant.emoji,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

private val REACTION_BUBBLE_EDGE_INSET = 12.dp
private val REACTION_BUBBLE_OVERLAP = 21.dp
