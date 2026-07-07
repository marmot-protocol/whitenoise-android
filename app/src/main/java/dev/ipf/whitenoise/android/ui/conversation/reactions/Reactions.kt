package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.state.ReactionParticipant
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerSheet
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * One consolidated reaction pill: the distinct emojis clustered together with a
 * total count, mirroring the familiar messenger style — a single compact target
 * rather than a spread of separate chips. Tapping opens the reactor list, where
 * a reaction can be removed.
 */
@Composable
internal fun ReactionSummaryChip(
    tallies: List<ReactionTally>,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val mine = tallies.any { it.mine }
    val total = tallies.sumOf { it.count }
    val emojis = tallies.take(MAX_VISIBLE_REACTIONS).joinToString(separator = "") { it.emoji }
    val viewReactorsLabel = stringResource(R.string.view_reactors)
    val border =
        if (isAmoledSurfaceTheme()) {
            BorderStroke(1.dp, colorScheme.outlineVariant)
        } else {
            BorderStroke(1.5.dp, colorScheme.surface)
        }
    Surface(
        modifier =
            Modifier
                // Expose the "you reacted" state to TalkBack, not just via color.
                .semantics { selected = mine }
                .clip(RoundedCornerShape(percent = 50))
                .clickable(role = Role.Button, onClick = onClick, onClickLabel = viewReactorsLabel),
        shape = RoundedCornerShape(percent = 50),
        color = if (mine) colorScheme.secondaryContainer else colorScheme.surfaceContainerHigh,
        contentColor = if (mine) colorScheme.onSecondaryContainer else colorScheme.onSurface,
        border = border,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(emojis, style = MaterialTheme.typography.labelLarge)
            if (total > 1) {
                Text(total.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactionDetailsSheet(
    participants: List<ReactionParticipant>,
    appState: WhiteNoiseAppState,
    onRemoveOwnReaction: ((String) -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    var selectedEmoji by remember(participants) { mutableStateOf<String?>(null) }
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

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = amoledSheetContainerColor(),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomizeReactionsDialog(
    quickReactionEmojis: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onReset: () -> Unit,
) {
    var draft by remember(quickReactionEmojis) { mutableStateOf(RecentEmojiList.normalizeQuickChoices(quickReactionEmojis)) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
            ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.customize_reactions)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(32.dp),
                        border = amoledSurfaceBorderStroke(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            draft.forEachIndexed { index, emoji ->
                                Box(
                                    modifier =
                                        Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .clickable { editingIndex = index },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(emoji, style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.customize_reactions_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            draft = RecentEmojiList.DefaultQuickChoices
                            onReset()
                        },
                    ) {
                        Text(stringResource(R.string.reset_reactions))
                    }
                    Button(onClick = { onSave(draft) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
        if (editingIndex != null) {
            EmojiPickerSheet(
                onDismissRequest = { editingIndex = null },
                onEmojiPicked = { emoji ->
                    val index = editingIndex ?: return@EmojiPickerSheet
                    draft = draft.toMutableList().also { it[index] = emoji }
                    editingIndex = null
                },
                recordRecentPicks = false,
            )
        }
    }
}

// Distinct emojis shown in the consolidated reaction pill; the total count
// still reflects every reaction beyond them.
private const val MAX_VISIBLE_REACTIONS = 4
