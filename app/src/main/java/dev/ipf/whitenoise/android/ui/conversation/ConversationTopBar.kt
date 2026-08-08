package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchTopBar
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingDropdownMenu
import dev.ipf.whitenoise.android.ui.design.conversationMenuItemPadding
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
internal fun ConversationTopBar(
    selectionMode: Boolean,
    selectedCount: Int,
    canCopySelection: Boolean,
    canForwardSelection: Boolean,
    onCloseSelection: () -> Unit,
    onCopySelection: () -> Unit,
    onForwardSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    searchOpen: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchAction: () -> Unit,
    searchFocusRequester: FocusRequester,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    groupTitleCopy: GroupTitleCopy,
    openedAsDmHint: Boolean,
    openDetailsDescription: String,
    onOpenDetails: () -> Unit,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenSearch: () -> Unit,
    onToggleArchived: () -> Unit,
    onRequestLeave: () -> Unit,
) {
    Column {
        if (selectionMode) {
            MessageSelectionBar(
                count = selectedCount,
                canCopy = canCopySelection,
                canForward = canForwardSelection,
                onClose = onCloseSelection,
                onCopy = onCopySelection,
                onForward = onForwardSelection,
                onDelete = onDeleteSelection,
            )
        } else if (searchOpen) {
            ConversationSearchTopBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onClear = onClearSearch,
                onClose = onCloseSearch,
                onSearchAction = onSearchAction,
                focusRequester = searchFocusRequester,
            )
        } else {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onOpenDetails)
                                .semantics { contentDescription = openDetailsDescription },
                    ) {
                        GroupAvatar(
                            appState = appState,
                            group = controller.group,
                            title = controller.title(groupTitleCopy),
                            seed = controller.avatarAccount ?: controller.group.groupIdHex,
                            size = 36.dp,
                            fallbackPictureUrl = controller.avatarAccount?.let(appState::avatarUrl),
                        )
                        Column {
                            Text(
                                controller.title(groupTitleCopy),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val membersSubtitle =
                                if (
                                    shouldShowConversationMembersSubtitle(
                                        membersLoaded = controller.membersLoaded,
                                        openedAsDmHint = openedAsDmHint,
                                        groupName = controller.group.name,
                                        memberCount = controller.memberCount,
                                    )
                                ) {
                                    controller.subtitle(
                                        justYou = stringResource(R.string.just_you),
                                        oneMember = stringResource(R.string.one_member),
                                        membersFormat = stringResource(R.string.members_count),
                                    )
                                } else {
                                    null
                                }
                            val disappearingSecs = controller.group.disappearingMessageSecs.toLong()
                            val showTimer = disappearingSecs > 0L
                            if (membersSubtitle != null || showTimer) {
                                val labelStyle = MaterialTheme.typography.labelSmall
                                val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                val subtitleRow: @Composable () -> Unit = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (membersSubtitle != null) {
                                            Text(membersSubtitle, style = labelStyle, color = labelColor)
                                        }
                                        if (showTimer) {
                                            if (membersSubtitle != null) {
                                                Text("·", style = labelStyle, color = labelColor)
                                            }
                                            Icon(
                                                Icons.Default.Schedule,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = labelColor,
                                            )
                                            Text(
                                                disappearingMessagesLabel(disappearingSecs),
                                                style = labelStyle,
                                                color = labelColor,
                                            )
                                        }
                                    }
                                }
                                if (showTimer) {
                                    val timerTooltipState = rememberTooltipState(isPersistent = true)
                                    val timerTooltipText = stringResource(R.string.disappearing_tooltip_text)
                                    val showTooltipOnce =
                                        remember(controller.group.groupIdHex) {
                                            !appState.disappearingTooltipShown
                                        }
                                    if (showTooltipOnce) {
                                        LaunchedEffect(controller.group.groupIdHex) {
                                            appState.markDisappearingTooltipShown()
                                            timerTooltipState.show()
                                        }
                                    }
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
                                        tooltip = { RichTooltip { Text(timerTooltipText) } },
                                        state = timerTooltipState,
                                        content = subtitleRow,
                                    )
                                } else {
                                    subtitleRow()
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onMenuOpenChange(true) }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.chat_actions),
                        )
                    }
                    KeyboardPreservingDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { onMenuOpenChange(false) },
                        shape = RoundedCornerShape(20.dp),
                        offset = DpOffset(x = (-8).dp, y = 0.dp),
                        modifier = Modifier.widthIn(min = 232.dp),
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.conversation_search_open),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            contentPadding = conversationMenuItemPadding,
                            onClick = onOpenSearch,
                        )
                        if (!controller.group.pendingConfirmation) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (controller.group.archived) R.string.unarchive else R.string.archive,
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                contentPadding = conversationMenuItemPadding,
                                enabled = !controller.mutationInFlight,
                                onClick = onToggleArchived,
                            )
                            if (controller.isSelfMember) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.leave),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    },
                                    contentPadding = conversationMenuItemPadding,
                                    enabled = !controller.mutationInFlight && controller.membersLoaded,
                                    onClick = onRequestLeave,
                                )
                            }
                        }
                    }
                },
            )
        }
        TtsTransportBar(appState)
    }
}
