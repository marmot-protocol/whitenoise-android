package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
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
import dev.ipf.whitenoise.android.core.chatListItemEvicted
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.OutgoingMessageIndicator
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.common.longPressOrVerticalDrag
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.rememberMessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.messages.OutgoingIndicatorIcon
import dev.ipf.whitenoise.android.ui.rememberMarkdownPreviewText

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.chatListSelectionRowClickable(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick, onLongClick = {})

internal fun Modifier.chatListSelectionRow(
    selected: Boolean,
    onClick: () -> Unit,
): Modifier =
    fillMaxWidth()
        .semantics { this.selected = selected }
        .chatListSelectionRowClickable(onClick)

/**
 * A stationary long press opens the single-chat actions surface. Continuing
 * the hold into a vertical drag enters anchored range selection; while
 * selection is active, tap toggles a row and long press is a no-op.
 */
@Composable
internal fun ChatListRow(
    item: ChatListItem,
    appState: WhiteNoiseAppState,
    isMuted: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenActions: () -> Unit,
    onDragSelectionStart: (Float) -> Unit,
    onDragSelection: (Float) -> Boolean,
    onDragSelectionEnd: () -> Unit,
    onDragSelectionCancel: () -> Unit,
    rangeDragActive: Boolean,
    onToggleSelection: () -> Unit,
    // Non-null when this row matched the chat-list search on a message body
    // (issue #290); drives the highlighted snippet line under the row.
    bodyMatch: MessageBodyMatch? = null,
) {
    ChatRow(
        item = item,
        appState = appState,
        selectionMode = selectionMode,
        selected = selected,
        onClick =
            if (selectionMode) {
                onToggleSelection
            } else {
                onOpen
            },
        onLongClick =
            if (selectionMode && !rangeDragActive) {
                null
            } else {
                onOpenActions
            },
        onDragSelectionStart = onDragSelectionStart,
        onDragSelection = onDragSelection,
        onDragSelectionEnd = onDragSelectionEnd,
        onDragSelectionCancel = onDragSelectionCancel,
        rangeDragActive = rangeDragActive,
        onOpenProfile = onOpenProfile,
        bodyMatch = bodyMatch,
        isMuted = isMuted,
    )
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
    onOpenProfile: (String) -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDragSelectionStart: ((Float) -> Unit)? = null,
    onDragSelection: ((Float) -> Boolean)? = null,
    onDragSelectionEnd: (() -> Unit)? = null,
    onDragSelectionCancel: (() -> Unit)? = null,
    rangeDragActive: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    isMuted: Boolean = false,
    // Message-body search hit for this row (issue #290): when present, a
    // second supporting line shows the matched message with the needle
    // highlighted, so the user can see why the chat appeared in the results.
    bodyMatch: MessageBodyMatch? = null,
) {
    val haptics = LocalHapticFeedback.current
    val rowCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val actionsLabel = stringResource(R.string.actions)

    fun pointerWindowY(position: Offset): Float? = rowCoordinates[0]?.localToWindow(position)?.y

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
            ?.takeIf { item.isDm() }
    val rowModifier =
        when {
            selectionMode && !rangeDragActive ->
                Modifier.chatListSelectionRow(
                    selected = selected,
                    onClick = onClick,
                )
            onLongClick != null ->
                Modifier
                    .clickable(onClick = onClick)
                    .longPressOrVerticalDrag(
                        onLongPressStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onLongPressRelease = { onLongClick() },
                        onDragStart = { position ->
                            pointerWindowY(position)?.let { onDragSelectionStart?.invoke(it) }
                        },
                        onDrag = { position ->
                            pointerWindowY(position)?.let { onDragSelection?.invoke(it) } == true
                        },
                        onDragEnd = { onDragSelectionEnd?.invoke() },
                        onGestureCancel = { onDragSelectionCancel?.invoke() },
                    ).semantics {
                        onLongClick(label = actionsLabel) {
                            onLongClick()
                            true
                        }
                    }.onGloballyPositioned { rowCoordinates[0] = it }
            else -> Modifier.clickable(onClick = onClick)
        }
    val pinned = item.pinned()
    val evicted = chatListItemEvicted(item)
    val hasSupportingMetadata = item.group.pendingConfirmation || rowHasUnread || pinned || evicted
    val actionColors = accountActionColors(appState)
    Box(modifier = rowModifier) {
        ChatRowLayout(
            modifier = Modifier.fillMaxWidth(),
            title = title,
            timestampAt = timestampAt,
            rowHasUnread = rowHasUnread,
            selectionMode = selectionMode,
            selected = selected,
            leadingContent = {
                Box(
                    modifier =
                        if (!selectionMode && openableDmAvatarAccount != null) {
                            Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button) {
                                    onOpenProfile(appState.npub(openableDmAvatarAccount))
                                }
                        } else {
                            Modifier
                        },
                ) {
                    GroupAvatar(
                        appState = appState,
                        group = item.group,
                        title = title,
                        seed = avatarAccount ?: item.group.groupIdHex,
                        size = 48.dp,
                        fallbackPictureUrl = avatarAccount?.let { appState.avatarUrl(it) },
                    )
                    if (isMuted) {
                        Icon(
                            imageVector = Icons.Default.NotificationsOff,
                            contentDescription = stringResource(R.string.chat_muted_badge),
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(2.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            supportingContent = supportingContent@{
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
                    ChatRowPreviewLine(
                        preview = preview,
                        fontStyle = if (draft != null) FontStyle.Italic else FontStyle.Normal,
                        deliveryIndicator =
                            item
                                .projectedDeliveryIndicator()
                                ?.takeIf { draft == null && !item.group.pendingConfirmation },
                    )
                }
            },
            supportingMetadata =
                if (hasSupportingMetadata) {
                    {
                        ChatRowSupportingMetadata(
                            pendingConfirmation = item.group.pendingConfirmation,
                            rowHasUnread = rowHasUnread,
                            rowUnreadCount = rowUnreadCount,
                            unreadMention = item.unreadMention,
                            actionColors = actionColors,
                            pinned = pinned,
                            evicted = evicted,
                        )
                    }
                } else {
                    null
                },
        )
        if (selectionMode) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            Color.Transparent
                        },
                    ),
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun ChatRowPreviewLine(
    preview: AnnotatedString,
    fontStyle: FontStyle,
    deliveryIndicator: OutgoingMessageIndicator?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = preview,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontStyle = fontStyle,
            modifier = Modifier.weight(1f),
        )
        if (deliveryIndicator != null) {
            val statusDescription =
                stringResource(
                    when (deliveryIndicator) {
                        OutgoingMessageIndicator.Sending -> R.string.sending
                        OutgoingMessageIndicator.Sent -> R.string.sent
                        OutgoingMessageIndicator.Failed -> R.string.send_failed
                    },
                )
            Spacer(Modifier.width(3.dp))
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .semantics { contentDescription = statusDescription },
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = deliveryIndicator,
                    animationSpec = tween(durationMillis = 150),
                    label = "chat row delivery indicator",
                ) { indicator ->
                    Box(Modifier.clearAndSetSemantics {}) {
                        OutgoingIndicatorIcon(
                            indicator = indicator,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun PinnedBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.PushPin,
        contentDescription = stringResource(R.string.chat_pinned_badge),
        modifier = modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Quiet status marker for a conversation the local account was evicted from.
 * Deliberately a tonal label rather than a [Badge]: the accent colours next to
 * it carry unread alerts, and this is a settled state, not something to act on.
 */
@Suppress("FunctionNaming")
@Composable
internal fun EvictedLabel(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.chat_row_removed_description)
    Surface(
        modifier = modifier.semantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(R.string.chat_row_removed_badge),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
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
