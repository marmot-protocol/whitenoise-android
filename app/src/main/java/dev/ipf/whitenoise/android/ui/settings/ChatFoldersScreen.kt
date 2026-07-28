package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                    state.folders.forEach { folder ->
                        ChatFolderManageRow(
                            folder = folder,
                            onMoveUp = { onMove(folder.id, -1) },
                            onMoveDown = { onMove(folder.id, +1) },
                            onEdit = { onEdit(folder.id) },
                            onDelete = { onDelete(folder.id) },
                        )
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
@Suppress("FunctionNaming")
private fun ChatFolderManageRow(
    folder: ChatFolderManageItem,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(folder.id) { mutableStateOf(false) }

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(folder.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.chat_folder_chat_count, folder.chatCount, folder.chatCount))
        },
        trailingContent = {
            Row {
                IconButton(onClick = onMoveUp, enabled = folder.canMoveUp) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.chat_folder_move_up),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = folder.canMoveDown) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.chat_folder_move_down),
                    )
                }
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
