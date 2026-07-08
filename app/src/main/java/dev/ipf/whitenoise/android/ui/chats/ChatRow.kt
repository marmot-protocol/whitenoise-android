package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MessageBodyMatch
import dev.ipf.whitenoise.android.core.SnippetHighlight
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.rememberMessageTextCopy
import dev.ipf.whitenoise.android.ui.common.rememberedRelativeTime
import dev.ipf.whitenoise.android.ui.rememberMarkdownPreviewText
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * Chat row with a long-press action menu (Archive / Unarchive,
 * Mark-as-read).
 *
 * Archiving is reached only through this menu and the in-conversation
 * menu — there is no swipe-to-archive gesture on the chat list. The
 * gesture was removed because it kept firing accidentally during
 * vertical scrolls and successive hardening passes never made it
 * reliably intentional. [onMenuArchiveToggle] runs the archive toggle,
 * whose controller call shows the plain confirmation toast.
 */
@Composable
internal fun ChatRowWithMenu(
    item: ChatListItem,
    appState: WhiteNoiseAppState,
    onOpen: () -> Unit,
    onMenuArchiveToggle: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    // Non-null when this row matched the chat-list search on a message body
    // (issue #290); drives the highlighted snippet line under the row.
    bodyMatch: MessageBodyMatch? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ChatRow(
            item = item,
            appState = appState,
            onClick = onOpen,
            onLongClick = { menuOpen = true },
            bodyMatch = bodyMatch,
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = MenuDefaults.shape,
            border = amoledSurfaceBorderStroke(),
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (item.group.archived) {
                                R.string.chat_row_action_unarchive
                            } else {
                                R.string.chat_row_action_archive
                            },
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        if (item.group.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuOpen = false
                    onMenuArchiveToggle()
                },
            )
            if (item.hasUnread) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_row_action_mark_read)) },
                    leadingIcon = { Icon(Icons.Default.MarkChatRead, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMarkRead()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.chat_row_action_delete_group),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * Render a [SnippetHighlight] as an [AnnotatedString] with the matched needle
 * range styled by [highlight]. The range is guaranteed valid by
 * `ChatListMessageSearch.buildSnippet`, but we clamp defensively so a future
 * change to the snippet builder can never throw here on the chat-list hot path.
 */
private fun highlightedSnippet(
    snippet: SnippetHighlight,
    highlight: SpanStyle,
): AnnotatedString {
    val text = snippet.text
    val start = snippet.highlightStart.coerceIn(0, text.length)
    val end = snippet.highlightEnd.coerceIn(start, text.length)
    return buildAnnotatedString {
        append(text)
        if (end > start) addStyle(highlight, start, end)
    }
}

@Composable
internal fun ChatRow(
    item: ChatListItem,
    appState: WhiteNoiseAppState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    // Message-body search hit for this row (issue #290): when present, a
    // second supporting line shows the matched message with the needle
    // highlighted, so the user can see why the chat appeared in the results.
    bodyMatch: MessageBodyMatch? = null,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    val messageTextCopy = rememberMessageTextCopy()
    // derivedStateOf so the title is only recomputed when its snapshot reads
    // (item, the profile-presentation revision read inside chatMemberTitle)
    // actually change, instead of every chat-list recomposition pass.
    // Routed through the shared `chatListItemDisplayTitle` so the same
    // projection drives the search filter.
    val title by remember(item, groupTitleCopy) {
        derivedStateOf { chatListItemDisplayTitle(item, appState, groupTitleCopy) }
    }
    // Membership-aware unread state (#625): a group the local user has been
    // removed from keeps a frozen unread badge in the projection. Suppress it
    // once the cached roster confirms self is no longer a member.
    val activeAccountIdHex = appState.activeAccount?.accountIdHex
    val rowHasUnread = item.effectiveHasUnread(activeAccountIdHex)
    val rowUnreadCount = item.effectiveUnreadCount(activeAccountIdHex)
    // On a message-body search hit (#594) the row's timestamp points at the
    // matched message, which may be far older than the conversation's last
    // activity. A title/preview-only hit (bodyMatch null) keeps the chat's
    // last-message time.
    val timestampAt = bodyMatch?.timelineAt ?: item.latestAt ?: 0uL
    val avatarAccount =
        GroupProjector.avatarAccount(
            group = item.group,
            otherMemberAccount = item.otherMemberAccount,
            memberCount = item.memberCount,
        )
    val openableDmAvatarAccount =
        avatarAccount
            ?.takeIf { GroupProjector.isDm(memberCount = item.memberCount, name = item.group.name) }
    val rowModifier =
        if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
            Modifier.clickable(onClick = onClick)
        }
    ListItem(
        modifier = rowModifier,
        leadingContent = {
            Box(
                modifier =
                    if (openableDmAvatarAccount != null) {
                        Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button) {
                                appState.presentProfile(appState.npub(openableDmAvatarAccount))
                            }
                    } else {
                        Modifier
                    },
            ) {
                Avatar(
                    title = title,
                    seed = avatarAccount ?: item.group.groupIdHex,
                    size = 44.dp,
                    // A group's own avatar URL wins over the member-derived avatar.
                    pictureUrl = item.group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) },
                )
            }
        },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val draft = appState.draftFor(item.group.groupIdHex)?.takeIf { it.isNotBlank() }
            // Tokens only ever describe the last message's body, so they're
            // ignored whenever the line shows something else (invite copy,
            // draft). When the controller hasn't parsed yet (or the parse
            // produced nothing), fall back to today's plaintext line. No
            // parsing happens here — composition stays parse-free.
            val markdownPreview =
                item.previewTokens
                    ?.takeIf { !item.group.pendingConfirmation && draft == null && it.blocks.isNotEmpty() }
            val preview =
                if (markdownPreview != null) {
                    rememberMarkdownPreviewText(
                        markdownPreview,
                        mentionDisplayName =
                            remember(appState) {
                                { bech32: String -> appState.mentionDisplayName(bech32) }
                            },
                    )
                } else {
                    AnnotatedString(
                        when {
                            item.group.pendingConfirmation -> stringResource(R.string.invitation)
                            draft != null -> stringResource(R.string.chat_row_draft_prefix) + draft
                            else ->
                                item.projectedPreviewText(
                                    copy = messageTextCopy,
                                    empty = stringResource(R.string.no_messages_yet),
                                )
                        },
                    )
                }
            // A body-content hit makes the matched message itself the subtitle:
            // the highlighted snippet replaces the last-message preview (its
            // timestamp already rides `timestampAt` above), so the line the user
            // reads is the one that actually matched. Title/preview-only hits
            // (bodyMatch null) keep the normal last-message preview.
            if (bodyMatch != null) {
                val highlightStyle =
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                val snippetText =
                    remember(bodyMatch.snippet, highlightStyle) {
                        highlightedSnippet(bodyMatch.snippet, highlightStyle)
                    }
                Text(
                    text = snippetText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontStyle = if (draft != null) FontStyle.Italic else FontStyle.Normal,
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    rememberedRelativeTime(timestampAt),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (rowHasUnread) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (item.group.pendingConfirmation) {
                    Badge { Text(stringResource(R.string.invited)) }
                } else if (rowHasUnread) {
                    // Surface the highest-signal unread: an @ badge beside the
                    // count when one of the unread messages mentions you (#611).
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.unreadMention) MentionBadge()
                        UnreadCountBadge(rowUnreadCount)
                    }
                }
            }
        },
    )
}

@Composable
internal fun MentionBadge(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.chat_list_mention_badge)
    // Tertiary accent so a mention reads as distinct from the primary-colored
    // unread count sitting beside it.
    Badge(
        modifier = modifier.semantics { contentDescription = description },
        containerColor = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    ) {
        Text("@")
    }
}
