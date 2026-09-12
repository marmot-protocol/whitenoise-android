package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import java.util.Locale

internal data class ChatFolderManageItem(
    val id: String,
    val name: String,
    val systemKind: SystemFolderKind?,
    val chatCount: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val description: String = "",
)

internal data class ChatFoldersState(
    val folders: List<ChatFolderManageItem>,
    val defaultsMissing: Boolean,
)

internal fun chatFoldersState(
    folders: List<ChatFolderManageItem>,
    defaultsMissing: Boolean = true,
): ChatFoldersState = ChatFoldersState(folders, defaultsMissing)

internal const val CHAT_FOLDERS_CONTENT_TAG = "chat-folders-content"

/**
 * Folders: every folder as its own group row with the live chat count, an actions menu (Edit, Move Up, Move Down,
 * Delete) that long-press also opens, a `+` in the top bar, and Restore Default Folders while a default is missing.
 * Creating or editing swaps in [ChatFolderEditScreen] in place, so the Settings navigation state never has to know.
 */
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
    val groupTitleCopy = rememberGroupTitleCopy()
    // Keyword rules match rendered row titles; subscribe like ChatsScreen's folder resolver.
    val profileRevision = appState.profileRevisionForCompose
    var editorOpenFor by remember { mutableStateOf<ChatFolderEditorTarget?>(null) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

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
                        displayTitle = { chatListItemDisplayTitle(it, appState, groupTitleCopy) },
                    ),
                canMoveUp = folders.firstOrNull()?.id != folder.id,
                canMoveDown = folders.lastOrNull()?.id != folder.id,
                description = folder.description,
            )
        }
    val defaultsMissing = SystemFolderKind.entries.any { kind -> folders.none { it.systemKind == kind } }

    ChatFoldersContent(
        state = chatFoldersState(folderItems, defaultsMissing),
        onBack = onBack,
        onCreate = { editorOpenFor = ChatFolderEditorTarget(folderId = null) },
        onMove = { id, delta ->
            folders.firstOrNull { it.id == id }?.let { move(it, delta) }
        },
        onEdit = { id -> editorOpenFor = ChatFolderEditorTarget(folderId = id) },
        onDelete = { id -> pendingDelete = id },
        onRestoreDefaults = { accountRef?.let(store::restoreDefaultFolders) },
    )

    folders.firstOrNull { it.id == pendingDelete }?.let { folder ->
        WhiteNoiseAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.folder_delete_title, chatFolderDisplayName(folder))) },
            text = { Text(stringResource(R.string.folder_delete_detail)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        accountRef?.let { store.deleteFolder(it, folder.id) }
                    },
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The list without any state ownership, so tests can render every folder arrangement. */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun ChatFoldersContent(
    state: ChatFoldersState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onMove: (String, Int) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.chat_folders_title),
        onBack = onBack,
        topBarActions = {
            IconButton(onClick = onCreate) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.chat_folder_new))
            }
        },
    ) {
        SettingsList(modifier = Modifier.testTag(CHAT_FOLDERS_CONTENT_TAG)) {
            if (state.folders.isEmpty()) {
                item {
                    WhiteNoiseEmptyState(
                        title = stringResource(R.string.folder_none),
                        detail = stringResource(R.string.folder_none_detail),
                    )
                }
            }
            state.folders.forEach { folder ->
                item(key = folder.id) {
                    SettingsGroup {
                        row(folder.id) { context ->
                            FolderManageRow(
                                context = context,
                                folder = folder,
                                onEdit = { onEdit(folder.id) },
                                onMove = { onMove(folder.id, it) },
                                onDelete = { onDelete(folder.id) },
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup {
                    row("restore") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.chat_folder_restore_defaults),
                            onClick = onRestoreDefaults,
                            enabled = state.defaultsMissing,
                        )
                    }
                }
                SettingsExplainer(stringResource(R.string.folder_restore_hint))
            }
        }
    }
}

/** One folder: icon, name over "N chats · description", and the actions menu; long-press opens the same menu. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
private fun FolderManageRow(
    context: SettingsRowContext,
    folder: ChatFolderManageItem,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember(folder.id) { mutableStateOf(false) }
    val name = folder.displayName()
    val editLabel = stringResource(R.string.folder_edit)
    val choices =
        buildList {
            add(WhiteNoiseMenuItem(editLabel, onClick = onEdit))
            if (folder.canMoveUp) {
                add(WhiteNoiseMenuItem(stringResource(R.string.chat_folder_move_up), onClick = { onMove(-1) }))
            }
            if (folder.canMoveDown) {
                add(WhiteNoiseMenuItem(stringResource(R.string.chat_folder_move_down), onClick = { onMove(1) }))
            }
            add(WhiteNoiseMenuItem(stringResource(R.string.delete), onClick = onDelete, destructive = true))
        }
    val count = pluralStringResource(R.plurals.chat_folder_chat_count, folder.chatCount, folder.chatCount)
    val supporting =
        count +
            folder.description
                .takeIf { it.isNotBlank() }
                ?.let { " · $it" }
                .orEmpty()
    SettingsGroupPanel(context, Modifier.testTag("folder.row.${folder.id}")) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = editLabel,
                    role = Role.Button,
                    onLongClick = { menu = true },
                    onClick = onEdit,
                ).semantics {
                    customActions =
                        choices.map { choice ->
                            CustomAccessibilityAction(choice.label) {
                                choice.onClick()
                                true
                            }
                        }
                }.padding(
                    start = FolderRowInset,
                    top = FolderRowVertical,
                    end = FolderRowMenuInset,
                    bottom = FolderRowVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolderRowInset),
        ) {
            Icon(
                painterResource(R.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier.size(FolderIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.actions_for, name))
                }
                WhiteNoiseDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, items = choices)
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
            isMuted = { groupIdHex -> source.any { it.group.groupIdHex == groupIdHex && it.engineMuted() } },
            displayTitle = displayTitle,
        )
    return source.count { it.group.groupIdHex.lowercase(Locale.ROOT) in ids }
}

private val FolderRowInset = 16.dp
private val FolderRowMenuInset = 4.dp
private val FolderRowVertical = 8.dp
private val FolderIconSize = 24.dp
