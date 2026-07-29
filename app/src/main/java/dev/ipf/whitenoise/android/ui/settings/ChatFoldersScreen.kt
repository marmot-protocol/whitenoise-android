package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatMutePreferences
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import java.util.Locale
import kotlin.math.roundToInt

internal data class ChatFolderManageItem(
    val id: String,
    val name: String,
    val systemKind: SystemFolderKind?,
    val chatCount: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

internal data class ChatFoldersState(
    val folders: List<ChatFolderManageItem>,
)

internal fun chatFoldersState(folders: List<ChatFolderManageItem>): ChatFoldersState = ChatFoldersState(folders)

internal const val CHAT_FOLDERS_CONTENT_TAG = "chat-folders-content"

/**
 * Settings detail screen managing chat folders: every folder — seeded
 * defaults and custom alike — with its live chat count and reorder, edit,
 * and delete controls. Deleted defaults stay deleted; an explicit Restore
 * action re-adds whichever are missing. Creating or editing swaps in
 * [ChatFolderEditScreen] in place, so the Settings navigation state never
 * has to know about the form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ChatFoldersScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val accountRef = appState.activeAccountRef
    val store = appState.chatFolderPreferences
    val storeState by store.state.collectAsState()
    val folders = remember(storeState, accountRef) { accountRef?.let(store::foldersFor).orEmpty() }
    val chatNotificationState by appState.chatMutePreferences.state.collectAsState()
    val mutedConversations = chatNotificationState.mutedConversations
    val groupTitleCopy = rememberGroupTitleCopy()
    // Keyword rules match rendered row titles; subscribe like ChatsScreen's folder resolver.
    val profileRevision = appState.profileRevisionForCompose
    var editorOpenFor by remember { mutableStateOf<ChatFolderEditorTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<ChatFolder?>(null) }

    val editor = editorOpenFor
    if (editor != null && accountRef != null) {
        ChatFolderEditScreen(
            appState = appState,
            accountRef = accountRef,
            folderId = editor.folderId,
            onClose = { editorOpenFor = null },
        )
        return
    }

    fun move(
        folder: ChatFolder,
        delta: Int,
    ) {
        val ids = folders.map { it.id }.toMutableList()
        val from = ids.indexOf(folder.id)
        val to = from + delta
        val validMove = from >= 0 && to >= 0 && to < ids.size
        if (accountRef != null && validMove) {
            ids.add(to, ids.removeAt(from))
            store.reorderFolders(accountRef, ids)
        }
    }

    val folderItems =
        folders.map { folder ->
            ChatFolderManageItem(
                id = folder.id,
                name = folder.name,
                systemKind = folder.systemKind,
                chatCount =
                    folderChatCount(
                        folder = folder,
                        appState = appState,
                        accountRef = accountRef,
                        mutedConversations = mutedConversations,
                        displayTitle = { chatListItemDisplayTitle(it, appState, groupTitleCopy) },
                    ),
                canMoveUp = folders.firstOrNull()?.id != folder.id,
                canMoveDown = folders.lastOrNull()?.id != folder.id,
            )
        }

    ChatFoldersContent(
        state = chatFoldersState(folderItems),
        onBack = onBack,
        onCreate = { editorOpenFor = ChatFolderEditorTarget(folderId = null) },
        onMove = { id, delta ->
            folders.firstOrNull { it.id == id }?.let { move(it, delta) }
        },
        onEdit = { id -> editorOpenFor = ChatFolderEditorTarget(folderId = id) },
        onDelete = { id -> pendingDelete = folders.firstOrNull { it.id == id } },
        onRestoreDefaults = { accountRef?.let(store::restoreDefaultFolders) },
    )

    pendingDelete?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.chat_folder_delete_title),
            message = stringResource(R.string.chat_folder_delete_message, chatFolderDisplayName(folder)),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = {
                pendingDelete = null
                accountRef?.let { store.deleteFolder(it, folder.id) }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ChatFoldersContent(
    state: ChatFoldersState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onMove: (String, Int) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val reorder = remember(haptics) { FolderReorderState(haptics) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_folders_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_folder_new))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .testTag(CHAT_FOLDERS_CONTENT_TAG),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard(title = stringResource(R.string.chat_folders_title)) {
                    val ids = state.folders.map { it.id }
                    state.folders.forEachIndexed { index, folder ->
                        Box(
                            modifier =
                                Modifier
                                    .zIndex(if (folder.id == reorder.draggedFolderId) 1f else 0f)
                                    .onSizeChanged { reorder.rowHeightsPx[folder.id] = it.height }
                                    .graphicsLayer { translationY = reorder.translationFor(index, ids) },
                        ) {
                            ChatFolderManageRow(
                                folder = folder,
                                onMoveUp = { onMove(folder.id, -1) },
                                onMoveDown = { onMove(folder.id, +1) },
                                onEdit = { onEdit(folder.id) },
                                onDelete = { onDelete(folder.id) },
                                onDragStart = { reorder.start(folder.id) },
                                onDragBy = reorder::dragBy,
                                onDragEnd = { commit -> reorder.end(commit, ids, onMove) },
                            )
                        }
                    }
                }
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.chat_folder_restore_defaults),
                    subtitle = stringResource(R.string.chat_folder_restore_defaults_subtitle),
                    onClick = onRestoreDefaults,
                )
            }
        }
    }
}

private data class ChatFolderEditorTarget(
    val folderId: String?,
)

/**
 * Drag bookkeeping for the reorder list: the row being dragged, its live
 * offset, measured row heights, and the slot arithmetic the row visuals and
 * the drop commit share. Rows between the origin and the current slot shift
 * aside visually; the drop commits the crossed slots as one reorder.
 */
private class FolderReorderState(
    private val haptics: HapticFeedback,
) {
    var draggedFolderId by mutableStateOf<String?>(null)
        private set
    private var dragOffsetPx by mutableFloatStateOf(0f)
    val rowHeightsPx = mutableStateMapOf<String, Int>()

    fun start(folderId: String) {
        draggedFolderId = folderId
        dragOffsetPx = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dragBy(deltaPx: Float) {
        dragOffsetPx += deltaPx
    }

    fun end(
        commit: Boolean,
        orderedIds: List<String>,
        onMove: (String, Int) -> Unit,
    ) {
        val id = draggedFolderId
        val shift = slotShift(orderedIds)
        draggedFolderId = null
        dragOffsetPx = 0f
        if (commit && id != null && shift != 0) onMove(id, shift)
    }

    fun translationFor(
        index: Int,
        orderedIds: List<String>,
    ): Float {
        val draggedIndex = orderedIds.indexOf(draggedFolderId)
        val height = (draggedFolderId?.let { rowHeightsPx[it] } ?: 0).toFloat()
        val shift = slotShift(orderedIds)
        return when {
            draggedIndex < 0 -> 0f
            index == draggedIndex -> dragOffsetPx
            index in (draggedIndex + 1)..(draggedIndex + shift) -> -height
            index in (draggedIndex + shift) until draggedIndex -> height
            else -> 0f
        }
    }

    private fun slotShift(orderedIds: List<String>): Int {
        val id = draggedFolderId ?: return 0
        val height = rowHeightsPx[id] ?: 0
        val index = orderedIds.indexOf(id)
        return if (height <= 0 || index < 0) {
            0
        } else {
            (dragOffsetPx / height).roundToInt().coerceIn(-index, orderedIds.lastIndex - index)
        }
    }
}

@Composable
internal fun chatFolderDisplayName(folder: ChatFolder): String = chatFolderDisplayName(folder.systemKind, folder.name)

@Composable
private fun ChatFolderManageItem.displayName(): String = chatFolderDisplayName(systemKind, name)

@Composable
private fun chatFolderDisplayName(
    systemKind: SystemFolderKind?,
    name: String,
): String =
    when (systemKind) {
        SystemFolderKind.UNREAD -> stringResource(R.string.chat_list_filter_unread)
        SystemFolderKind.ARCHIVED -> stringResource(R.string.archived)
        SystemFolderKind.GROUPS -> stringResource(R.string.chat_list_filter_groups)
        null -> name
    }

// Counts what selecting the folder's chip would show, so this stays in
// lockstep with the chip row's hide-when-empty and filtering decisions:
// every folder's count derives from its rule, defaults included.
private fun folderChatCount(
    folder: ChatFolder,
    appState: WhiteNoiseAppState,
    accountRef: String?,
    mutedConversations: Set<String>,
    displayTitle: (ChatListItem) -> String,
): Int {
    if (accountRef == null) return 0
    val rule = appState.chatFolderPreferences.folderRule(accountRef, folder.id)
    val archivedSource = rule?.archivedOnly == true
    val source = if (archivedSource) appState.archivedChatListItems else appState.chatListItems
    val ids =
        chatFolderChatIds(
            items = source,
            manualChatIds = appState.chatFolderPreferences.membershipFor(accountRef, folder.id),
            rule = rule,
            activeAccountIdHex = appState.activeAccount?.accountIdHex,
            isMuted = { ChatMutePreferences.compositeKey(accountRef, it) in mutedConversations },
            displayTitle = displayTitle,
        )
    return source.count { it.group.groupIdHex.lowercase(Locale.ROOT) in ids }
}

@Composable
@Suppress("FunctionNaming", "LongParameterList")
private fun ChatFolderManageRow(
    folder: ChatFolderManageItem,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: (commit: Boolean) -> Unit,
) {
    var menuOpen by remember(folder.id) { mutableStateOf(false) }
    // The drag gesture has no TalkBack equivalent, so the old up/down moves
    // survive as custom accessibility actions on the row.
    val moveActions =
        folderMoveActions(
            folder = folder,
            moveUpLabel = stringResource(R.string.chat_folder_move_up),
            moveDownLabel = stringResource(R.string.chat_folder_move_down),
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
        )

    ListItem(
        modifier =
            Modifier
                .semantics { customActions = moveActions }
                .clickable(
                    onClickLabel = stringResource(R.string.edit),
                    role = Role.Button,
                    onClick = onEdit,
                ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(folder.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.chat_folder_chat_count, folder.chatCount, folder.chatCount))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatFolderDragHandle(
                    folderId = folder.id,
                    onDragStart = onDragStart,
                    onDragBy = onDragBy,
                    onDragEnd = onDragEnd,
                )
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
}

private fun folderMoveActions(
    folder: ChatFolderManageItem,
    moveUpLabel: String,
    moveDownLabel: String,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
): List<CustomAccessibilityAction> =
    buildList {
        if (folder.canMoveUp) {
            add(
                CustomAccessibilityAction(moveUpLabel) {
                    onMoveUp()
                    true
                },
            )
        }
        if (folder.canMoveDown) {
            add(
                CustomAccessibilityAction(moveDownLabel) {
                    onMoveDown()
                    true
                },
            )
        }
    }

@Composable
@Suppress("FunctionNaming")
private fun ChatFolderDragHandle(
    folderId: String,
    onDragStart: () -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: (commit: Boolean) -> Unit,
) {
    // The drag callbacks are read from inside a pointerInput block that
    // outlives recompositions, so route them through updated state.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragBy by rememberUpdatedState(onDragBy)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Icon(
        Icons.Default.DragHandle,
        contentDescription = stringResource(R.string.chat_folder_drag_to_reorder),
        modifier =
            Modifier
                .pointerInput(folderId) {
                    detectDragGestures(
                        onDragStart = { currentOnDragStart() },
                        onDrag = { change, amount ->
                            change.consume()
                            currentOnDragBy(amount.y)
                        },
                        onDragEnd = { currentOnDragEnd(true) },
                        onDragCancel = { currentOnDragEnd(false) },
                    )
                }.padding(12.dp),
    )
}
