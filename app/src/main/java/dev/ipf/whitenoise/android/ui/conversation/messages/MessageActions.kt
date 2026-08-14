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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.chatFolderTriState
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.rememberEncryptedGroupAvatar
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.resolveMentionsInPlaintext
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.Locale

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
    canSelect: Boolean,
    canCopyText: Boolean,
    canSpeak: Boolean,
    canSelectText: Boolean,
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
    onSave: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    if (!expanded) return

    val density = LocalDensity.current
    val actionKinds =
        remember(canReply, canEdit, canSelect, canSelectText, canCopyText, canSpeak, canForward, canSave, canInfo) {
            messageActionKinds(
                canReply = canReply,
                canEdit = canEdit,
                canSelect = canSelect,
                canSelectText = canSelectText,
                canCopyText = canCopyText,
                canSpeak = canSpeak,
                canForward = canForward,
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
            maxOf(48.dp, actionTextStyle.lineHeight.toDp() + 16.dp)
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
                                                    MessageActionKind.Save -> onSave()
                                                    MessageActionKind.Info -> onInfo()
                                                    null -> onDelete()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
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
            MessageActionKind.Save -> Icons.Default.Download
            MessageActionKind.Info -> Icons.Default.Info
        }
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
}

/**
 * Chat-picker sheet for forwarding a message into one or more other chats
 * (issue #390). Multi-select, searchable, recent-first. Confirming fans the
 * message out to every selected chat as an independent fresh send (see
 * [WhiteNoiseAppState.forwardText]) — each target is re-encrypted under its own
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

private fun forwardFolderBulkRows(
    appState: WhiteNoiseAppState,
    targets: List<ChatListItem>,
    groupTitleCopy: GroupTitleCopy,
): List<Pair<ChatFolder, List<String>>> {
    val accountRef = appState.activeAccountRef ?: return emptyList()
    val store = appState.chatFolderPreferences
    val mutedGroupIds = targets.filter(ChatListItem::engineMuted).mapTo(hashSetOf()) { it.group.groupIdHex }
    return store
        .foldersFor(accountRef)
        // These rows render and query-match the stored name, so folders
        // without one (un-renamed defaults) have nothing to show here; a
        // renamed default participates like any other folder.
        .filter { it.name.isNotBlank() }
        .map { folder ->
            folder to
                chatFolderChatIds(
                    items = targets,
                    manualChatIds = store.membershipFor(accountRef, folder.id),
                    rule = store.folderRule(accountRef, folder.id),
                    activeAccountIdHex = appState.activeAccount?.accountIdHex,
                    isMuted = { groupIdHex ->
                        groupIdHex in mutedGroupIds
                    },
                    displayTitle = { chatListItemDisplayTitle(it, appState, groupTitleCopy) },
                ).toList()
        }.filter { (_, memberIds) -> memberIds.size >= 2 }
}

// No rows at all only when there are no chat rows to show AND no folder rows
// matched — a query hitting only a folder name must still render that folder.
private fun forwardPickerHasNoRows(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardMessageSheet(
    appState: WhiteNoiseAppState,
    body: String,
    originGroupIdHex: String,
    onDismiss: () -> Unit,
    onForward: (List<String>) -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    // Snapshot the forward targets once when the sheet opens. The chat list is
    // a live projection, but a picker that re-sorts under the user's finger as
    // a background send confirms would shuffle rows mid-selection; a stable
    // snapshot keeps the selection anchored to the rows the user actually saw.
    val targets =
        remember {
            appState.forwardTargets().filterNot { it.group.groupIdHex.equals(originGroupIdHex, ignoreCase = true) }
        }
    val titledTargets =
        remember(targets, groupTitleCopy) {
            targets.map { it to chatListItemDisplayTitle(it, appState, groupTitleCopy) }
        }
    // Folders as bulk-select shortcuts: one row per custom folder with at
    // least two valid targets (a 0-1 chat folder is a no-op as a bulk
    // action). Selecting one checks its members into the same `selected`
    // list, so individual chats stay independently toggleable afterwards.
    // Snapshotted once, matching the target snapshot's stability rationale.
    val folderBulkRows =
        remember(targets, groupTitleCopy) {
            forwardFolderBulkRows(appState, targets, groupTitleCopy)
        }
    val filtered =
        remember(titledTargets, query) {
            val needle = query.trim()
            if (needle.isEmpty()) {
                titledTargets
            } else {
                titledTargets.filter { (_, title) -> title.contains(needle, ignoreCase = true) }
            }
        }
    // Opens at half height with a drag up to full — a long chat list stays
    // reachable without the sheet swallowing the conversation behind it.
    val sheetState = rememberModalBottomSheetState()
    var searchFocused by remember { mutableStateOf(false) }
    val expanded = sheetState.currentValue == SheetValue.Expanded || sheetState.targetValue == SheetValue.Expanded
    val targetListMaxHeight = if (expanded) 420.dp else 152.dp
    LaunchedEffect(searchFocused) {
        if (searchFocused) sheetState.expand()
    }
    val activeAccountIdHex = appState.activeAccount?.accountIdHex
    val forwardPreviewText =
        remember(body, appState.profileRevisionForCompose) {
            resolveMentionsInPlaintext(body) { appState.mentionDisplayName(it) }
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.forward_to),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // Preview of what is being forwarded, so the user can confirm the
            // content before fanning it out to several chats.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = amoledSurfaceBorderStroke(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
            ) {
                // Preview only: resolve raw profile mention runs to display
                // names so the confirmation reads like the bubble (#615/#1090).
                // The forwarded text stays the verbatim `body` — onForward
                // never sees this string.
                Text(
                    forwardPreviewText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            FlowSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.forward_search_chats),
                modifier =
                    Modifier
                        .padding(horizontal = Dimens.spaceLg)
                        .onFocusChanged { searchFocused = it.isFocused },
            )
            // A query that matches only a folder name must still surface that
            // folder row, so emptiness is judged across chats AND folders.
            val visibleFolderRows = visibleForwardFolderRows(folderBulkRows, query)
            LazyColumn(
                modifier =
                    Modifier
                        .heightIn(max = targetListMaxHeight)
                        .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = Dimens.spaceLg),
            ) {
                if (forwardPickerHasNoRows(targets.isEmpty(), filtered.isEmpty(), visibleFolderRows.isEmpty())) {
                    item {
                        Text(
                            stringResource(
                                if (targets.isEmpty()) R.string.forward_no_chats else R.string.forward_no_matches,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                        )
                    }
                } else {
                    forwardFolderSection(visibleFolderRows, selected)
                    if (filtered.isNotEmpty()) item { SectionHeader(stringResource(R.string.recent_chats)) }
                    items(filtered, key = { (item, _) -> item.group.groupIdHex }) { (item, title) ->
                        val groupId = item.group.groupIdHex.lowercase(Locale.ROOT)
                        val isSelected = selected.contains(groupId)
                        val avatarAccount = forwardTargetAvatarAccount(item)
                        // Group rows preview the other members' names, mirroring
                        // the chat-list mental model; direct chats need none.
                        val membersPreview =
                            remember(item, appState.profileRevisionForCompose) {
                                forwardTargetMembersPreview(item, activeAccountIdHex) { memberIdHex ->
                                    appState.contactDisplayNameCached(memberIdHex)
                                }
                            }
                        ContactRow(
                            title = title,
                            subtitle = membersPreview,
                            avatarSeed = avatarAccount ?: item.group.groupIdHex,
                            avatarUrl = item.group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) },
                            avatarImage = rememberEncryptedGroupAvatar(appState, item.group),
                            onClick = {
                                if (isSelected) selected.remove(groupId) else selected.add(groupId)
                            },
                            trailing = { SelectionIndicator(selected = isSelected) },
                        )
                    }
                }
            }

            Surface(
                color = amoledSheetContainerColor(),
                shadowElevation = 6.dp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .imePadding(),
            ) {
                Button(
                    onClick = {
                        onForward(forwardRecipientGroupIds(selected, originGroupIdHex))
                        onDismiss()
                    },
                    enabled = selected.isNotEmpty(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.spaceLg, vertical = 12.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Forward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selected.isEmpty()) {
                            stringResource(R.string.forward)
                        } else {
                            pluralStringResource(
                                R.plurals.forward_to_chats_count,
                                selected.size,
                                selected.size,
                            )
                        },
                    )
                }
            }
        }
    }
}

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
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
    minimumHeight: Dp = 48.dp,
) {
    TextButton(
        onClick = onClick,
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
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
