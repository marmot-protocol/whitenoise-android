package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatMutePreferences
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.theme.Dimens
import java.util.Locale

/**
 * Settings detail screen managing chat folders: every folder (system and
 * custom) with its live chat count and reorder controls; custom folders are
 * also editable and deletable. Creating or editing swaps in
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
    // null = list, non-null = the create/edit form (folderId null = create).
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
            FloatingActionButton(onClick = { editorOpenFor = ChatFolderEditorTarget(folderId = null) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_folder_new))
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Dimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            items(folders, key = { it.id }) { folder ->
                val custom = !folder.isSystem
                ChatFolderManageRow(
                    model =
                        ChatFolderManageRowModel(
                            displayName = chatFolderDisplayName(folder),
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
                        ),
                    onMoveUp = { move(folder, -1) },
                    onMoveDown = { move(folder, +1) },
                    onEdit =
                        if (custom) {
                            { editorOpenFor = ChatFolderEditorTarget(folderId = folder.id) }
                        } else {
                            null
                        },
                    onDelete = if (custom) ({ pendingDelete = folder }) else null,
                )
            }
        }
    }

    pendingDelete?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.chat_folder_delete_title),
            message = stringResource(R.string.chat_folder_delete_message, folder.name),
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

private data class ChatFolderEditorTarget(
    val folderId: String?,
)

private data class ChatFolderManageRowModel(
    val displayName: String,
    val chatCount: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

@Composable
internal fun chatFolderDisplayName(folder: ChatFolder): String =
    when (folder.systemKind) {
        SystemFolderKind.UNREAD -> stringResource(R.string.chat_list_filter_unread)
        SystemFolderKind.ARCHIVED -> stringResource(R.string.archived)
        SystemFolderKind.GROUPS -> stringResource(R.string.chat_list_filter_groups)
        null -> folder.name
    }

// Counts what selecting the folder's chip would show, so this stays in
// lockstep with the chip row's hide-when-empty and filtering decisions.
private fun folderChatCount(
    folder: ChatFolder,
    appState: WhiteNoiseAppState,
    accountRef: String?,
    mutedConversations: Set<String>,
    displayTitle: (ChatListItem) -> String,
): Int {
    val active = appState.chatListItems
    return when (folder.systemKind) {
        SystemFolderKind.UNREAD -> active.count { it.hasUnread }
        SystemFolderKind.ARCHIVED -> appState.archivedChatListItems.size
        SystemFolderKind.GROUPS -> active.count { !GroupProjector.isDm(it.memberCount, it.group.name) }
        null -> {
            if (accountRef == null) {
                0
            } else {
                val ids =
                    chatFolderChatIds(
                        items = active,
                        manualChatIds = appState.chatFolderPreferences.membershipFor(accountRef, folder.id),
                        rule = appState.chatFolderPreferences.folderRule(accountRef, folder.id),
                        isMuted = { ChatMutePreferences.compositeKey(accountRef, it) in mutedConversations },
                        displayTitle = displayTitle,
                    )
                active.count { it.group.groupIdHex.lowercase(Locale.ROOT) in ids }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ChatFolderManageRow(
    model: ChatFolderManageRowModel,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    ListItem(
        modifier = Modifier.settingsRowAmoledSurfaceBorder(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.chat_folder_chat_count, model.chatCount, model.chatCount))
        },
        trailingContent = {
            Row {
                IconButton(onClick = onMoveUp, enabled = model.canMoveUp) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.chat_folder_move_up),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = model.canMoveDown) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.chat_folder_move_down),
                    )
                }
                if (onEdit != null) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                }
            }
        },
    )
}
