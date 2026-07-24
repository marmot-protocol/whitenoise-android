package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatMutePreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.chatFolderTriState
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.chats.newchat.FlowSearchField
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectionIndicator
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.resolveMentionsInPlaintext
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

@Composable
internal fun MessageActionMenu(
    expanded: Boolean,
    anchorWindowYPx: Float?,
    alignEnd: Boolean,
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
    val density = LocalDensity.current
    // Position the popup purely from the captured window touch y, independent of
    // any anchor's layout position. DropdownMenu derived flip-above from the
    // anchor's bounds, so a bubble taller than the viewport (anchor off-screen)
    // could send the menu above the finger even with room below (#326). This
    // provider clamps/flips against the window directly.
    val edgeInsetPx = with(density) { 8.dp.roundToPx() }
    // Compose runs calculatePosition on the FIRST layout pass with
    // popupContentSize == (0,0) (content not yet measured). With height 0 the
    // "fits below" branch is always true, so a near-top message would place
    // the menu top AT touchY on frame 1, then flip/clamp once the real tall
    // height arrives — a visible above-then-below jump (#389). Decide the side
    // deterministically from frame 1 by feeding a non-zero height into the
    // provider: the real measured height once known, else a per-variant
    // estimate derived from the menu's own layout so frame 1 already matches
    // the height the side decision will settle on.
    // Key to expanded so a previous menu variant's measured height cannot win
    // over the new variant's estimate on the first frame after reopening.
    var measuredPopupHeightPx by remember(expanded) { mutableStateOf(0) }
    // First-frame fallback only. A flat constant (the previous 240.dp) both
    // overestimated short menus — flipping them above even when they fit
    // below — and underestimated tall menus, so the measured height could
    // still flip the side on frame 2 (the same jump #389 set out to remove,
    // see #517). Instead, predict the height from the exact menu layout:
    //   - one emoji/quick-reaction Row (36.dp)
    //   - a HorizontalDivider (1.dp)
    //   - the action buttons (each 48.dp min) in a spacedBy(2.dp) Column:
    //       Info always; +Select text when canSelectText; +Copy when canCopyText;
    //       +Reply when canReply; +Edit when canEdit;
    //       +Forward when canForward; +Select when canSelect;
    //       +Delete when canDelete (scope is chosen on the delete surface)
    //   - the outer Column's 8.dp padding (top + bottom) and its two
    //     spacedBy(8.dp) gaps between the three sections.
    // Keep this in sync with the menu Column below if its layout changes.
    val estimatedPopupHeightPx =
        with(density) {
            val actionButtonCount =
                1 +
                    (if (canSelectText) 1 else 0) +
                    (if (canCopyText) 1 else 0) +
                    (if (canSpeak) 1 else 0) +
                    (if (canReply) 1 else 0) +
                    (if (canEdit) 1 else 0) +
                    (if (canForward) 1 else 0) +
                    (if (canSelect) 1 else 0) +
                    (if (canSave) 1 else 0) +
                    (if (canDelete) 1 else 0)
            val actionsColumnHeight = (actionButtonCount * 48).dp + ((actionButtonCount - 1).coerceAtLeast(0) * 2).dp
            val reactionSectionHeight = if (canReact) 36.dp + 1.dp + 8.dp else 0.dp
            val totalHeight = (8.dp + 8.dp) + 8.dp + reactionSectionHeight + actionsColumnHeight
            totalHeight.roundToPx()
        }
    val positionProvider =
        remember(anchorWindowYPx, alignEnd, edgeInsetPx, measuredPopupHeightPx, estimatedPopupHeightPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val touchY = anchorWindowYPx?.roundToInt() ?: (windowSize.height / 2)
                    // Horizontal: hug the bubble side, clamped inside the window.
                    val x =
                        if (alignEnd) {
                            windowSize.width - popupContentSize.width - edgeInsetPx
                        } else {
                            edgeInsetPx
                        }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                    // Decide vertical placement against a non-zero height so the
                    // chosen side is stable from the first frame. Once the popup
                    // has any real measurement (content or onSizeChanged), use it
                    // directly so the *settled* placement reflects the true menu
                    // height; the per-variant estimate is consulted only on the
                    // first frame before either is known (#517).
                    val measuredHeight = maxOf(popupContentSize.height, measuredPopupHeightPx)
                    val effectiveHeight =
                        if (measuredHeight > 0) measuredHeight else estimatedPopupHeightPx
                    // Vertical: top at the touch y; flip upward if it would spill
                    // past the bottom inset; if it still doesn't fit, clamp to top.
                    val bottomLimit = windowSize.height - edgeInsetPx
                    val y =
                        when {
                            touchY + effectiveHeight <= bottomLimit -> touchY
                            effectiveHeight <= touchY - edgeInsetPx -> touchY - effectiveHeight
                            else -> edgeInsetPx
                        }.coerceIn(edgeInsetPx, (windowSize.height - effectiveHeight).coerceAtLeast(0))
                    return IntOffset(x, y)
                }
            }
        }
    KeyboardSafePopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        popupPositionProvider = positionProvider,
    ) {
        // Surface restores the menu chrome (rounded shape + elevation) that
        // DropdownMenu provided.
        Surface(
            modifier = Modifier.onSizeChanged { measuredPopupHeightPx = it.height },
            shape = RoundedCornerShape(12.dp),
            border = amoledSurfaceBorderStroke(),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(8.dp).widthIn(min = 292.dp, max = 328.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canReact) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        quickReactionEmojis.forEach { emoji ->
                            EmojiActionButton(
                                emoji = emoji,
                                onClick = { onReact(emoji) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        IconButton(
                            onClick = onOpenEmojiPicker,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.EmojiEmotions,
                                contentDescription = stringResource(R.string.open_emoji_picker),
                            )
                        }
                    }
                    HorizontalDivider()
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (canReply) {
                        MessageActionButton(
                            label = stringResource(R.string.reply),
                            icon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onReply,
                        )
                    }
                    if (canEdit) {
                        MessageActionButton(
                            label = stringResource(R.string.edit),
                            icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onEdit,
                        )
                    }
                    if (canSelect) {
                        MessageActionButton(
                            label = stringResource(R.string.select),
                            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onSelect,
                        )
                    }
                    if (canSelectText) {
                        MessageActionButton(
                            label = stringResource(R.string.select_text),
                            icon = { Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onSelectText,
                        )
                    }
                    if (canCopyText) {
                        MessageActionButton(
                            label = stringResource(R.string.copy_text),
                            icon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onCopyText,
                        )
                    }
                    if (canSpeak) {
                        MessageActionButton(
                            label = stringResource(R.string.speak_aloud),
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = onSpeak,
                        )
                    }
                    if (canForward) {
                        MessageActionButton(
                            label = stringResource(R.string.forward),
                            icon = { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onForward,
                        )
                    }
                    if (canSave) {
                        MessageActionButton(
                            label = stringResource(R.string.shared_media_save),
                            icon = {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = onSave,
                        )
                    }
                    MessageActionButton(
                        label = stringResource(R.string.message_info),
                        icon = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = onInfo,
                    )
                    if (canDelete) {
                        // One Delete entry regardless of role or ownership;
                        // the delete surface it opens offers only the scopes
                        // the capability model permits.
                        MessageActionButton(
                            label = stringResource(R.string.delete),
                            icon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onDelete,
                            isDestructive = true,
                        )
                    }
                }
            }
        }
    }
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
        otherMemberAccount = item.otherMemberAccount,
        memberCount = item.memberCount,
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
    val mutedConversations = appState.chatMutePreferences.state.value.mutedConversations
    return store
        .foldersFor(accountRef)
        .filterNot { it.isSystem }
        .map { folder ->
            folder to
                chatFolderChatIds(
                    items = targets,
                    manualChatIds = store.membershipFor(accountRef, folder.id),
                    rule = store.folderRule(accountRef, folder.id),
                    isMuted = { groupIdHex ->
                        ChatMutePreferences.compositeKey(accountRef, groupIdHex) in mutedConversations
                    },
                    displayTitle = { chatListItemDisplayTitle(it, appState, groupTitleCopy) },
                ).toList()
        }.filter { (_, memberIds) -> memberIds.size >= 2 }
}

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
                    if (triState == ToggleableState.On) {
                        selected.removeAll(memberIds)
                    } else {
                        selected.addAll(memberIds.filterNot { it in selected })
                    }
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
                if ((targets.isEmpty() || filtered.isEmpty()) && visibleFolderRows.isEmpty()) {
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
                        val groupId = item.group.groupIdHex
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
                        onForward(selected.toList())
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
    Surface(
        modifier = modifier.height(36.dp).clip(CircleShape).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
        border = amoledSurfaceBorderStroke(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
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
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
