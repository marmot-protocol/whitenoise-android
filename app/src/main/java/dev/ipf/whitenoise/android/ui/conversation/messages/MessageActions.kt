@file:Suppress("TooManyFunctions") // One action surface owns menu, picker, progress, and button projections.

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ForwardFailureStage
import dev.ipf.whitenoise.android.state.ForwardOperationPhase
import dev.ipf.whitenoise.android.state.ForwardOperationSnapshot
import dev.ipf.whitenoise.android.state.ForwardTargetPhase
import dev.ipf.whitenoise.android.state.ForwardTargetProgress
import dev.ipf.whitenoise.android.state.PendingForwardRequest
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isForwardOwnerSignedIn
import dev.ipf.whitenoise.android.ui.chats.chatFolderTriState
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.UUID

/** Anchored long-press action menu for one message. */
@Composable
internal fun MessageActionMenu(
    expanded: Boolean,
    anchorBoundsInWindow: IntRect?,
    anchorWindowYPx: Float?,
    centerOverAnchor: Boolean = false,
    canReply: Boolean,
    canReact: Boolean,
    canDelete: Boolean,
    canEdit: Boolean,
    canForward: Boolean,
    forwardBlockedReason: ForwardBlockedReason? = null,
    canSelect: Boolean,
    canCopyText: Boolean,
    canSpeak: Boolean,
    canSelectText: Boolean,
    canShare: Boolean = false,
    canSave: Boolean,
    canInfo: Boolean = true,
    quickReactionEmojis: List<String>,
    onDismissRequest: () -> Unit,
    onReact: (String) -> Unit,
    onOpenEmojiPicker: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onSelect: () -> Unit,
    onSelectText: () -> Unit,
    onCopyText: () -> Unit,
    onSpeak: () -> Unit,
    onShare: () -> Unit = {},
    onSave: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    if (!expanded) return

    val density = LocalDensity.current
    val explainedForwardBlock = forwardBlockedReason?.takeUnless { it == ForwardBlockedReason.Unsupported }
    val showForwardAction = canForward || explainedForwardBlock != null
    val actionKinds =
        remember(
            canReply,
            canEdit,
            canSelect,
            canSelectText,
            canCopyText,
            canSpeak,
            showForwardAction,
            canShare,
            canSave,
            canInfo,
        ) {
            messageActionKinds(
                canReply = canReply,
                canEdit = canEdit,
                canSelect = canSelect,
                canSelectText = canSelectText,
                canCopyText = canCopyText,
                canSpeak = canSpeak,
                canForward = showForwardAction,
                canShare = canShare,
                canSave = canSave,
                canInfo = canInfo,
            )
        }
    val labeledActions: List<Pair<MessageActionKind?, String>> =
        buildList {
            actionKinds.forEach { kind -> add(kind to messageActionLabel(kind)) }
            if (canDelete) add(null to stringResource(R.string.delete))
        }
    val textMeasurer = rememberTextMeasurer()
    val actionTextStyle = MaterialTheme.typography.titleMedium
    val actionSupportingTextStyle = MaterialTheme.typography.bodySmall
    val minimumActionCellWidth =
        remember(labeledActions, actionTextStyle, density, textMeasurer) {
            with(density) {
                val widestLabelPx =
                    labeledActions.maxOf { (_, label) ->
                        textMeasurer.measure(AnnotatedString(label), style = actionTextStyle, maxLines = 1).size.width
                    }
                maxOf(136.dp, widestLabelPx.toDp() + 52.dp)
            }
        }
    val actionRowHeight =
        with(density) {
            maxOf(
                if (explainedForwardBlock == null) 48.dp else 64.dp,
                actionTextStyle.lineHeight.toDp() +
                    if (explainedForwardBlock == null) {
                        16.dp
                    } else {
                        actionSupportingTextStyle.lineHeight.toDp() * 2 + 16.dp
                    },
            )
        }
    val reactionRowHeight = 48.dp
    // Position from the frozen message bounds, not a screen corner. Centering
    // the reaction strip over the selected bubble gives images and text the
    // same stable visual anchor instead of pinning the surface to an outer
    // screen edge. Media menus center over the visual card; text menus retain
    // the familiar adjacent placement below the bubble (above only when the
    // bottom space is insufficient).
    val edgeInsetPx = with(density) { 8.dp.roundToPx() }
    val anchorGapPx = with(density) { 8.dp.roundToPx() }
    // Compose runs calculatePosition on the FIRST layout pass with
    // popupContentSize == (0,0) (content not yet measured). With height 0 the
    // "fits below" branch is always true, so a near-top message would place
    // the menu top AT touchY on frame 1, then flip/clamp once the real tall
    // height arrives — a visible above-then-below jump (#389). Decide the side
    // deterministically from frame 1 by feeding a non-zero height into the
    // provider a per-variant estimate derived from the same immutable action
    // model as the rendered grid. That same estimate caps the scrollable
    // surface and owns the final boundary clamp, so measurement cannot move
    // the popup on the following frame. Keep the content transparent until its
    // first non-zero measurement so users only see the settled surface (#1857).
    // First-frame fallback mirrors the measured responsive grid. Label widths
    // determine whether the estimate uses one or two columns, so large fonts
    // and long translations do not reintroduce the frame-two side flip.
    val estimatedOneColumnHeightPx =
        with(density) {
            estimatedMessageActionMenuHeight(
                actionCount = actionKinds.size,
                columns = 1,
                canReact = canReact,
                canDelete = canDelete,
                actionRowHeight = actionRowHeight,
                reactionRowHeight = reactionRowHeight,
            ).roundToPx()
        }
    val estimatedTwoColumnHeightPx =
        with(density) {
            estimatedMessageActionMenuHeight(
                actionCount = actionKinds.size,
                columns = 2,
                canReact = canReact,
                canDelete = canDelete,
                actionRowHeight = actionRowHeight,
                reactionRowHeight = reactionRowHeight,
            ).roundToPx()
        }
    val minimumActionCellWidthPx = with(density) { minimumActionCellWidth.roundToPx() }
    val maximumActionContentWidthPx = with(density) { 312.dp.roundToPx() }
    val actionContentPaddingPx = with(density) { 16.dp.roundToPx() }
    val actionColumnGapPx = with(density) { messageActionColumnGap.roundToPx() }
    val positionProvider =
        remember(
            anchorBoundsInWindow,
            anchorWindowYPx,
            centerOverAnchor,
            edgeInsetPx,
            anchorGapPx,
            estimatedOneColumnHeightPx,
            estimatedTwoColumnHeightPx,
            minimumActionCellWidthPx,
            maximumActionContentWidthPx,
            actionContentPaddingPx,
            actionColumnGapPx,
        ) {
            MessageActionMenuPositionProvider(
                anchorBoundsInWindow = anchorBoundsInWindow,
                anchorWindowYPx = anchorWindowYPx,
                centerOverAnchor = centerOverAnchor,
                edgeInsetPx = edgeInsetPx,
                anchorGapPx = anchorGapPx,
                estimatedOneColumnHeightPx = estimatedOneColumnHeightPx,
                estimatedTwoColumnHeightPx = estimatedTwoColumnHeightPx,
                minimumActionCellWidthPx = minimumActionCellWidthPx,
                maximumActionContentWidthPx = maximumActionContentWidthPx,
                actionContentPaddingPx = actionContentPaddingPx,
                actionColumnGapPx = actionColumnGapPx,
            )
        }
    var actionMenuMeasured by remember { mutableStateOf(false) }
    KeyboardSafePopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        popupPositionProvider = positionProvider,
    ) {
        // Surface restores the menu chrome (rounded shape + elevation) that
        // DropdownMenu provided.
        BoxWithConstraints {
            val menuWidth = minOf(328.dp, (maxWidth - 16.dp).coerceAtLeast(48.dp))
            val estimatedMenuHeight =
                if (messageActionColumnCount((menuWidth - 16.dp).coerceAtLeast(0.dp), minimumActionCellWidth) == 2) {
                    with(density) { estimatedTwoColumnHeightPx.toDp() }
                } else {
                    with(density) { estimatedOneColumnHeightPx.toDp() }
                }
            val boundedHeightModifier =
                if (constraints.hasBoundedHeight) {
                    Modifier.heightIn(
                        max = minOf(estimatedMenuHeight, (maxHeight - 16.dp).coerceAtLeast(48.dp)),
                    )
                } else {
                    Modifier.heightIn(max = estimatedMenuHeight)
                }
            Surface(
                modifier =
                    boundedHeightModifier
                        .width(menuWidth)
                        .onSizeChanged { size ->
                            if (size.width > 0 && size.height > 0) actionMenuMeasured = true
                        }.graphicsLayer(
                            alpha = if (actionMenuMeasured) 1f else 0f,
                        ).testTag(MESSAGE_ACTION_MENU_TEST_TAG),
                shape = RoundedCornerShape(12.dp),
                border = amoledSurfaceBorderStroke(),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canReact) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    quickReactionEmojis.forEach { emoji ->
                                        EmojiActionButton(
                                            emoji = emoji,
                                            onClick = { onReact(emoji) },
                                            modifier = Modifier.testTag("$MESSAGE_ACTION_REACTION_TEST_TAG:$emoji"),
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = onOpenEmojiPicker,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Surface(
                                        modifier = Modifier.size(40.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = CircleShape,
                                        border = amoledSurfaceBorderStroke(),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.EmojiEmotions,
                                                contentDescription = stringResource(R.string.open_emoji_picker),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            AppDivider()
                        }
                    }
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val columns = messageActionColumnCount(maxWidth, minimumActionCellWidth)
                        Column(verticalArrangement = Arrangement.spacedBy(messageActionColumnGap)) {
                            labeledActions.chunked(columns).forEach { rowActions ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(messageActionColumnGap),
                                ) {
                                    rowActions.forEach { (kind, label) ->
                                        MessageActionButton(
                                            label = label,
                                            supportingLabel =
                                                if (kind == MessageActionKind.Forward) {
                                                    explainedForwardBlock?.let { forwardBlockedReasonLabel(it) }
                                                } else {
                                                    null
                                                },
                                            icon = {
                                                if (kind == null) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                } else {
                                                    MessageActionIcon(kind)
                                                }
                                            },
                                            onClick = {
                                                when (kind) {
                                                    MessageActionKind.Reply -> onReply()
                                                    MessageActionKind.Edit -> onEdit()
                                                    MessageActionKind.Select -> onSelect()
                                                    MessageActionKind.SelectText -> onSelectText()
                                                    MessageActionKind.CopyText -> onCopyText()
                                                    MessageActionKind.Speak -> onSpeak()
                                                    MessageActionKind.Forward -> onForward()
                                                    MessageActionKind.Share -> onShare()
                                                    MessageActionKind.Save -> onSave()
                                                    MessageActionKind.Info -> onInfo()
                                                    null -> onDelete()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            enabled = kind != MessageActionKind.Forward || canForward,
                                            isDestructive = kind == null,
                                            minimumHeight = actionRowHeight,
                                        )
                                    }
                                    repeat(columns - rowActions.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun forwardBlockedReasonLabel(reason: ForwardBlockedReason): String =
    when (reason) {
        ForwardBlockedReason.PendingAttachment -> stringResource(R.string.forward_blocked_pending)
        ForwardBlockedReason.FailedAttachment -> stringResource(R.string.forward_blocked_failed)
        ForwardBlockedReason.MalformedAttachment -> stringResource(R.string.forward_blocked_malformed)
        ForwardBlockedReason.ExpiredAttachment -> stringResource(R.string.forward_blocked_expired)
        ForwardBlockedReason.UnavailableAttachment -> stringResource(R.string.forward_blocked_unavailable)
        ForwardBlockedReason.RestrictedAttachment -> stringResource(R.string.forward_blocked_restricted)
        ForwardBlockedReason.Unsupported -> stringResource(R.string.forward_blocked_unsupported)
    }

@Composable
@Suppress("FunctionNaming")
private fun MessageActionIcon(kind: MessageActionKind) {
    val icon =
        when (kind) {
            MessageActionKind.Reply -> Icons.AutoMirrored.Filled.Reply
            MessageActionKind.Edit -> Icons.Default.Edit
            MessageActionKind.Select -> Icons.Default.CheckCircle
            MessageActionKind.SelectText -> Icons.Default.TextFields
            MessageActionKind.CopyText -> Icons.Default.ContentCopy
            MessageActionKind.Speak -> Icons.AutoMirrored.Filled.VolumeUp
            MessageActionKind.Forward -> Icons.AutoMirrored.Filled.Forward
            MessageActionKind.Share -> Icons.Default.Share
            MessageActionKind.Save -> Icons.Default.Download
            MessageActionKind.Info -> Icons.Default.Info
        }
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
}

/**
 * Chat-picker sheet for forwarding a message into one or more other chats
 * (issue #390). Multi-select, searchable, recent-first. Confirming fans the
 * message out to every selected chat as an independent fresh send (see
 * [WhiteNoiseAppState.startForwardMessages]) — each target is re-encrypted under its own
 * group state, with no source-group key reuse and no original-sender / source
 * attribution carried across the boundary.
 *
 * The picker deliberately omits the chat the message came from
 * ([originGroupIdHex]): forwarding a message back into its own conversation is
 * never the intent and would just duplicate it.
 */
internal fun forwardTargetAvatarAccount(item: ChatListItem): String? =
    GroupProjector.avatarAccount(
        group = item.group,
        otherMemberAccount = item.presentationOtherMemberAccount,
        memberCount = item.presentationMemberCount,
    )

internal fun forwardTargetMembersPreview(
    item: ChatListItem,
    activeAccountIdHex: String?,
    memberTitle: (String) -> String,
): String? {
    if (forwardTargetAvatarAccount(item) != null) return null
    return item.memberSnapshot
        ?.members
        ?.filterNot { it.memberIdHex.equals(activeAccountIdHex, ignoreCase = true) }
        ?.map { memberTitle(it.memberIdHex) }
        ?.filter { it.isNotBlank() }
        ?.take(6)
        ?.joinToString(", ")
        ?.takeIf { it.isNotBlank() }
}

/**
 * Folder bulk-select rows restricted to the owning account's current eligible destinations.
 * Saved manual membership can contain the origin or removed chats, so intersect before
 * counting or toggling and retain the same destination order as the individual rows.
 */
internal fun forwardFolderBulkRows(
    appState: WhiteNoiseAppState,
    targets: List<ChatListItem>,
    groupTitleCopy: GroupTitleCopy,
    ownerAccountRef: String? = appState.activeAccountRef,
    ownerAccountIdHex: String? = appState.activeAccount?.accountIdHex,
): List<Pair<ChatFolder, List<String>>> {
    val accountRef = ownerAccountRef ?: return emptyList()
    val store = appState.chatFolderPreferences
    val eligibleIds =
        targets
            .map {
                it.group.groupIdHex
                    .trim()
                    .lowercase(Locale.ROOT)
            }.filter { it.isNotEmpty() }
            .distinct()
    val mutedGroupIds = targets.filter(ChatListItem::engineMuted).mapTo(hashSetOf()) { it.group.groupIdHex }
    return store
        .foldersFor(accountRef)
        // These rows render and query-match the stored name, so folders
        // without one (un-renamed defaults) have nothing to show here; a
        // renamed default participates like any other folder.
        .filter { it.name.isNotBlank() }
        .map { folder ->
            val memberIds =
                chatFolderChatIds(
                    items = targets,
                    manualChatIds = store.membershipFor(accountRef, folder.id),
                    rule = store.folderRule(accountRef, folder.id),
                    activeAccountIdHex = ownerAccountIdHex,
                    isMuted = { groupIdHex ->
                        groupIdHex in mutedGroupIds
                    },
                    displayTitle = { forwardTargetDisplayTitle(it, appState, accountRef, groupTitleCopy) },
                ).mapTo(hashSetOf()) { it.trim().lowercase(Locale.ROOT) }
            folder to eligibleIds.filter(memberIds::contains)
        }.filter { (_, memberIds) -> memberIds.size >= 2 }
}

/**
 * Chat title resolved against the account that owns the chat list, so a
 * non-active destination account's rows never borrow the active account's
 * contact-name caches.
 */
internal fun forwardTargetDisplayTitle(
    item: ChatListItem,
    appState: WhiteNoiseAppState,
    ownerAccountRef: String?,
    copy: GroupTitleCopy,
): String =
    when {
        ownerAccountRef == null || ownerAccountRef == appState.activeAccountRef ->
            chatListItemDisplayTitle(item, appState, copy)
        item.sanitizedNamedTitle != null -> item.sanitizedNamedTitle.orEmpty()
        else ->
            GroupProjector.displayTitle(
                group = item.group,
                otherMemberAccount = item.presentationOtherMemberAccount,
                memberCount = item.presentationMemberCount,
                memberTitle = { appState.contactDisplayNameCached(ownerAccountRef, it) },
                copy = copy,
                conversationKind = item.projection?.conversationKind,
                soleSelfMember = item.presentationActiveAccountIsSoleMember,
            )
    }

/**
 * True only when neither chat rows nor folder rows would render — no rows at
 * all only when there are no chat rows to show AND no folder rows matched,
 * because a query hitting only a folder name must still render that folder.
 */
internal fun forwardPickerHasNoRows(
    targetsEmpty: Boolean,
    filteredEmpty: Boolean,
    foldersEmpty: Boolean,
): Boolean = (targetsEmpty || filteredEmpty) && foldersEmpty

internal fun visibleForwardFolderRows(
    rows: List<Pair<ChatFolder, List<String>>>,
    query: String,
): List<Pair<ChatFolder, List<String>>> {
    if (query.isBlank()) return rows
    return rows.filter { (folder, _) -> folder.name.contains(query.trim(), ignoreCase = true) }
}

private fun LazyListScope.forwardFolderSection(
    rows: List<Pair<ChatFolder, List<String>>>,
    selected: SnapshotStateList<String>,
) {
    if (rows.isEmpty()) return
    item { SectionHeader(stringResource(R.string.chat_folders_title)) }
    items(rows, key = { (folder, _) -> "folder:" + folder.id }) { (folder, memberIds) ->
        val triState = chatFolderTriState(memberIds, selected.toSet())
        ListItem(
            modifier =
                Modifier.clickable {
                    val nextSelection = forwardSelectionAfterFolderToggle(selected, memberIds)
                    selected.clear()
                    selected.addAll(nextSelection)
                },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { TriStateCheckbox(state = triState, onClick = null) },
            headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(pluralStringResource(R.plurals.chat_folder_chat_count, memberIds.size, memberIds.size))
            },
        )
    }
}

@Composable
@Suppress("FunctionNaming", "LongMethod") // Exhaustive operation phases stay beside their progress semantics.
internal fun ForwardProgressContent(
    snapshot: ForwardOperationSnapshot,
    targetTitles: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val summary =
        when (snapshot.phase) {
            ForwardOperationPhase.Completed ->
                pluralStringResource(
                    R.plurals.forward_completed_summary,
                    snapshot.completedTargets,
                    snapshot.completedTargets,
                )
            ForwardOperationPhase.PartialFailure,
            ->
                stringResource(
                    R.string.forward_partial_summary,
                    snapshot.completedTargets,
                    snapshot.targets.size,
                )
            ForwardOperationPhase.Failed -> stringResource(R.string.forward_failed_summary)
            ForwardOperationPhase.Cancelled -> stringResource(R.string.forward_cancelled)
            ForwardOperationPhase.Cancelling -> stringResource(R.string.forward_cancelling)
            else -> stringResource(R.string.forward_progress_title)
        }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = Dimens.spaceLg).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (snapshot.phase == ForwardOperationPhase.Preparing && snapshot.totalAttachments > 0) {
            LinearProgressIndicator(
                progress = {
                    snapshot.preparedAttachments.toFloat() / snapshot.totalAttachments.toFloat()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
            )
            Text(
                text =
                    stringResource(
                        R.string.forward_preparing_attachments,
                        snapshot.preparedAttachments,
                        snapshot.totalAttachments,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            items(snapshot.targets, key = ForwardTargetProgress::groupIdHex) { progress ->
                ForwardTargetProgressRow(
                    progress = progress,
                    title =
                        targetTitles[progress.groupIdHex.lowercase(Locale.ROOT)]
                            ?: progress.groupIdHex.take(FORWARD_TARGET_TITLE_FALLBACK_LENGTH),
                )
            }
        }
    }
}

/** Projects a destination's transfer phase and actionable failure reason into one accessible row. */
@Composable
@Suppress("CyclomaticComplexMethod", "FunctionNaming", "LongMethod")
private fun ForwardTargetProgressRow(
    progress: ForwardTargetProgress,
    title: String,
) {
    val supportingText =
        when (progress.phase) {
            ForwardTargetPhase.Waiting -> stringResource(R.string.forward_waiting)
            ForwardTargetPhase.Uploading ->
                stringResource(
                    R.string.forward_uploading_attachments,
                    progress.uploadedAttachments,
                    progress.totalAttachments,
                )
            ForwardTargetPhase.Sending ->
                stringResource(
                    R.string.forward_sending_messages,
                    progress.sentMessages,
                    progress.totalMessages,
                )
            ForwardTargetPhase.Completed -> stringResource(R.string.forward_sent)
            ForwardTargetPhase.Cancelled ->
                if (progress.sentMessages > 0) {
                    stringResource(
                        R.string.forward_failed_after_partial,
                        stringResource(R.string.forward_cancelled),
                        progress.sentMessages,
                        progress.totalMessages,
                    )
                } else {
                    stringResource(R.string.forward_cancelled)
                }
            ForwardTargetPhase.Failed -> {
                val failure =
                    when (progress.failureStage) {
                        ForwardFailureStage.Upload -> stringResource(R.string.forward_failed_upload)
                        ForwardFailureStage.Publish -> stringResource(R.string.forward_failed_publish)
                        ForwardFailureStage.PayloadTooLarge -> stringResource(R.string.forward_payload_too_large)
                        ForwardFailureStage.Expired -> stringResource(R.string.forward_failed_expired)
                        ForwardFailureStage.SessionChanged -> stringResource(R.string.forward_failed_session_changed)
                        ForwardFailureStage.PreparationTimeout ->
                            stringResource(R.string.forward_failed_preparation_timeout)
                        ForwardFailureStage.Materialize,
                        null,
                        -> stringResource(R.string.forward_failed_preparing)
                    }
                if (progress.sentMessages > 0) {
                    stringResource(
                        R.string.forward_failed_after_partial,
                        failure,
                        progress.sentMessages,
                        progress.totalMessages,
                    )
                } else {
                    failure
                }
            }
        }
    ListItem(
        modifier =
            Modifier.semantics {
                stateDescription = supportingText
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(supportingText, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            when (progress.phase) {
                ForwardTargetPhase.Uploading,
                ForwardTargetPhase.Sending,
                -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                ForwardTargetPhase.Completed ->
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                ForwardTargetPhase.Failed ->
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                ForwardTargetPhase.Cancelled ->
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                ForwardTargetPhase.Waiting ->
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        },
    )
}

/**
 * One forwarding request bound to its source conversation owner. The picker's
 * destination account and chat selections are mirrored into the encrypted
 * no-backup pending-request store so process recreation can restore an
 * unresolved request; explicit dismissal or acceptance discards that entry.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ForwardMessageSheet(
    appState: WhiteNoiseAppState,
    payloads: List<ForwardMessagePayload>,
    sourceAccountRef: String?,
    originGroupIdHex: String,
    onDismiss: () -> Unit,
    restoredRequest: PendingForwardRequest? = null,
) {
    // Plain remember on purpose: the encrypted store owns recreation, and the
    // saved-state Bundle must not carry forward-request identifiers.
    val requestId =
        remember(originGroupIdHex) {
            restoredRequest?.requestId ?: UUID.randomUUID().toString()
        }
    var pickerTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val dismissAndDiscard = {
        appState.forwardRequestPersistence.discard(requestId)
        onDismiss()
    }
    ForwardMessagePickerFullScreen(
        appState = appState,
        messageCount = payloads.size,
        attachmentCount =
            payloads.sumOf { payload ->
                (payload as? ForwardMessagePayload.Media)?.attachments?.size ?: 0
            },
        originGroupIdHex = originGroupIdHex,
        sourceAccountRef = sourceAccountRef,
        onDismiss = dismissAndDiscard,
        onForward = { destinationAccountRef, targetGroupIds ->
            val started =
                sourceAccountRef != null &&
                    appState.startForwardMessages(
                        targetGroupIds = targetGroupIds,
                        messages = payloads,
                        sourceAccountRef = sourceAccountRef,
                        destinationAccountRef = destinationAccountRef,
                        targetTitles = pickerTitles,
                    )
            if (started) appState.forwardRequestPersistence.discard(requestId)
            started
        },
        initialDestinationAccountRef = restoredRequest?.destinationAccountRef,
        initialSelectedGroupIds = restoredRequest?.selectedGroupIds.orEmpty(),
        onPickerStateChanged = { destinationAccountRef, selectedGroupIds, titles ->
            pickerTitles = titles
            if (sourceAccountRef != null) {
                appState.forwardRequestPersistence.persist(
                    PendingForwardRequest(
                        requestId = requestId,
                        sourceAccountRef = sourceAccountRef,
                        originGroupIdHex = originGroupIdHex,
                        payloads = payloads,
                        destinationAccountRef = destinationAccountRef,
                        selectedGroupIds = selectedGroupIds,
                    ),
                )
            }
        },
    )
}

/** Outcome of validating one persisted forward request against a conversation. */
internal enum class RestoredForwardDisposition {
    Restore,
    Ignore,
    Discard,
}

/**
 * Decides whether a persisted forward request may be restored here. A request
 * belonging to another conversation or another source owner is ignored, and a
 * request whose source owner or previously chosen destination owner is no
 * longer a signed-in signing account is discarded — restoration must never
 * silently substitute another account for either bound owner.
 */
internal fun restoredForwardRequestDisposition(
    request: PendingForwardRequest,
    boundGroupIdHex: String,
    boundAccountRef: String?,
    isOwnerSignedIn: (String) -> Boolean,
): RestoredForwardDisposition =
    when {
        !request.originGroupIdHex.equals(boundGroupIdHex, ignoreCase = true) ->
            RestoredForwardDisposition.Ignore
        request.sourceAccountRef != boundAccountRef -> RestoredForwardDisposition.Ignore
        !isOwnerSignedIn(request.sourceAccountRef) -> RestoredForwardDisposition.Discard
        request.destinationAccountRef?.let { !isOwnerSignedIn(it) } == true ->
            RestoredForwardDisposition.Discard
        else -> RestoredForwardDisposition.Restore
    }

/**
 * Restores the single unresolved forward request after process recreation.
 * Restoration stays bound to the request's recorded source conversation and
 * source account and never substitutes another account for either bound
 * owner: a request whose source owner or previously chosen destination owner
 * is no longer a signed-in signing account is discarded with an explanation
 * instead of being restored.
 */
@Composable
@Suppress("FunctionNaming")
internal fun RestoredForwardRequestHost(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
) {
    var restored by remember { mutableStateOf<PendingForwardRequest?>(null) }
    LaunchedEffect(controller.group.groupIdHex, controller.boundAccountRef) {
        val request = appState.forwardRequestPersistence.load() ?: return@LaunchedEffect
        when (
            restoredForwardRequestDisposition(
                request = request,
                boundGroupIdHex = controller.group.groupIdHex,
                boundAccountRef = controller.boundAccountRef,
                isOwnerSignedIn = { appState.isForwardOwnerSignedIn(it) },
            )
        ) {
            RestoredForwardDisposition.Ignore -> Unit
            RestoredForwardDisposition.Discard -> {
                appState.forwardRequestPersistence.discard(request.requestId)
                appState.presentTransient(R.string.forward_restore_discarded)
            }
            RestoredForwardDisposition.Restore -> restored = request
        }
    }
    restored?.let { request ->
        ForwardMessageSheet(
            appState = appState,
            payloads = request.payloads,
            sourceAccountRef = request.sourceAccountRef,
            originGroupIdHex = request.originGroupIdHex,
            onDismiss = { restored = null },
            restoredRequest = request,
        )
    }
}

/** One circular quick-reaction emoji button. */
@Composable
private fun EmojiActionButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            border = amoledSurfaceBorderStroke(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(emoji, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
internal fun MessageActionButton(
    label: String,
    supportingLabel: String? = null,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    minimumHeight: Dp = 48.dp,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = minimumHeight),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors =
            ButtonDefaults.textButtonColors(
                contentColor =
                    if (isDestructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supportingLabel != null) {
                    Text(
                        supportingLabel,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
