package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.ReactionParticipant
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.design.BottomAnchoredPopupPositionProvider
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

/** Keeps a reactor-sheet filter while its emoji remains present in the live participant list. */
internal fun retainedReactionFilter(
    selectedEmoji: String?,
    participants: List<ReactionParticipant>,
): String? = selectedEmoji?.takeIf { emoji -> participants.any { it.emoji == emoji } }

/** Returns every reactor or only those matching the selected emoji. */
internal fun filteredReactionParticipants(
    participants: List<ReactionParticipant>,
    selectedEmoji: String?,
): List<ReactionParticipant> = selectedEmoji?.let { emoji -> participants.filter { it.emoji == emoji } } ?: participants

/** Counts reactors per emoji and orders the filters by popularity, then emoji. */
internal fun reactionEmojiCounts(participants: List<ReactionParticipant>): List<Pair<String, Int>> =
    participants
        .groupingBy { it.emoji }
        .eachCount()
        .toList()
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

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

/** Shows every reactor and always starts on the All filter. */
@Composable
internal fun ReactionDetailsSheet(
    participants: List<ReactionParticipant>,
    appState: WhiteNoiseAppState,
    onRemoveOwnReaction: ((String) -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    KeyboardSafePopup(
        expanded = true,
        onDismissRequest = onDismissRequest,
        popupPositionProvider = BottomAnchoredPopupPositionProvider,
    ) {
        ReactionDetailsContent(
            participants = participants,
            appState = appState,
            onRemoveOwnReaction = onRemoveOwnReaction,
        )
    }
}

/** Renders the stateful reactor filters and rows independently of the popup window that owns them. */
@Composable
@Suppress("FunctionNaming")
internal fun ReactionDetailsContent(
    participants: List<ReactionParticipant>,
    appState: WhiteNoiseAppState,
    onRemoveOwnReaction: ((String) -> Unit)?,
) {
    var selectedEmoji by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(participants, selectedEmoji) {
        val retainedFilter = retainedReactionFilter(selectedEmoji, participants)
        if (retainedFilter != selectedEmoji) selectedEmoji = retainedFilter
    }
    val activeAccountId = appState.activeAccount?.accountIdHex
    val emojiCounts = remember(participants) { reactionEmojiCounts(participants) }
    val visibleParticipants =
        remember(participants, selectedEmoji) { filteredReactionParticipants(participants, selectedEmoji) }

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
            ReactionFilterChips(
                selectedEmoji = selectedEmoji,
                emojiCounts = emojiCounts,
                participantCount = participants.size,
                onSelectedEmoji = { selectedEmoji = it },
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            ) {
                items(
                    visibleParticipants,
                    key = { participant -> "${participant.sender}:${participant.emoji}:${participant.reactedAt}" },
                ) { participant ->
                    val isMine = activeAccountId != null && participant.sender.equals(activeAccountId, ignoreCase = true)
                    ReactionParticipantRow(
                        participant = participant,
                        appState = appState,
                        mine = isMine,
                        onRemove =
                            if (isMine && onRemoveOwnReaction != null) {
                                { onRemoveOwnReaction(participant.emoji) }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}

/** Renders the All and per-emoji filters while reporting the user's current selection. */
@Composable
@Suppress("FunctionNaming")
private fun ReactionFilterChips(
    selectedEmoji: String?,
    emojiCounts: List<Pair<String, Int>>,
    participantCount: Int,
    onSelectedEmoji: (String?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedEmoji == null,
            onClick = { onSelectedEmoji(null) },
            label = { Text("${stringResource(R.string.reaction_filter_all)} · $participantCount") },
        )
        emojiCounts.forEach { (emoji, count) ->
            FilterChip(
                selected = selectedEmoji == emoji,
                onClick = { onSelectedEmoji(emoji) },
                label = { Text("$emoji $count") },
            )
        }
    }
}

/** Native Android reactor row with the original own-reaction removal and profile actions. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
private fun ReactionParticipantRow(
    participant: ReactionParticipant,
    appState: WhiteNoiseAppState,
    mine: Boolean,
    onRemove: (() -> Unit)?,
) {
    val shortIdentity =
        appState.shortNpub(participant.sender).ifBlank {
            IdentityFormatter.short(participant.sender, prefix = 10, suffix = 8)
        }
    val displayName = appState.displayName(participant.sender).ifBlank { shortIdentity }
    val headline: @Composable () -> Unit = {
        Text(
            text = if (mine) stringResource(R.string.you) else displayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val tapToRemove = stringResource(R.string.reaction_tap_to_remove)
    val supportingText = if (mine) tapToRemove else shortIdentity.takeUnless { it == displayName }
    val supporting: (@Composable () -> Unit)? =
        supportingText?.let { text ->
            { ReactionParticipantSupportingText(text) }
        }
    val leading: @Composable () -> Unit = {
        Avatar(
            title = displayName,
            seed = participant.sender,
            size = 48.dp,
            pictureUrl = appState.avatarUrl(participant.sender),
        )
    }
    val trailing: @Composable () -> Unit = {
        Text(text = participant.emoji, style = MaterialTheme.typography.headlineSmall)
    }
    val colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    if (mine && onRemove != null) {
        ListItem(
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth(),
            shapes = WhiteNoiseListItemDefaults.shapes(),
            colors = colors,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            content = headline,
        )
    } else {
        ListItem(
            onClick = { appState.presentProfile(appState.npub(participant.sender)) },
            modifier = Modifier.fillMaxWidth(),
            shapes = WhiteNoiseListItemDefaults.shapes(),
            colors = colors,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            content = headline,
        )
    }
}

/** Renders secondary reactor context without competing with the display name. */
@Composable
@Suppress("FunctionNaming")
private fun ReactionParticipantSupportingText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val REACTION_BUBBLE_EDGE_INSET = 12.dp
private val REACTION_BUBBLE_OVERLAP = 21.dp
