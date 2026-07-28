package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListSelectionBar(
    count: Int,
    archiveAction: ChatListBulkArchiveAction,
    actionsEnabled: Boolean,
    allVisibleSelected: Boolean,
    showMarkRead: Boolean,
    showMarkUnread: Boolean,
    showMuteToggle: Boolean,
    muted: Boolean,
    showPinToggle: Boolean,
    pinned: Boolean,
    showMovePinnedUp: Boolean,
    showMovePinnedDown: Boolean,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onAddToFolder: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onMuteToggle: () -> Unit,
    onPinToggle: () -> Unit,
    onMovePinned: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    val archiveLabelRes =
        when (archiveAction) {
            ChatListBulkArchiveAction.Archive -> R.string.archive
            ChatListBulkArchiveAction.Unarchive -> R.string.unarchive
        }
    val archiveIcon =
        when (archiveAction) {
            ChatListBulkArchiveAction.Archive -> Icons.Default.Archive
            ChatListBulkArchiveAction.Unarchive -> Icons.Default.Unarchive
        }
    val selectedCountDescription = pluralStringResource(R.plurals.chat_list_selected_count, count, count)
    TopAppBar(
        title = {
            Text(
                count.toString(),
                modifier = Modifier.semantics { contentDescription = selectedCountDescription },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
            }
        },
        actions = {
            IconButton(onClick = onArchive, enabled = actionsEnabled) {
                Icon(archiveIcon, contentDescription = stringResource(archiveLabelRes))
            }
            IconButton(onClick = onDelete, enabled = actionsEnabled) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
            IconButton(onClick = { overflowOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.actions))
            }
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
                shape = MenuDefaults.shape,
                border = amoledSurfaceBorderStroke(),
            ) {
                if (showMarkRead) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_row_action_mark_read)) },
                        leadingIcon = { Icon(Icons.Default.MarkChatRead, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onMarkRead()
                        },
                    )
                }
                if (showMarkUnread) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_row_action_mark_unread)) },
                        leadingIcon = { Icon(Icons.Default.MarkChatUnread, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onMarkUnread()
                        },
                    )
                }
                PinnedSelectionMenuItems(
                    showPinToggle = showPinToggle,
                    pinned = pinned,
                    showMovePinnedUp = showMovePinnedUp,
                    showMovePinnedDown = showMovePinnedDown,
                    onPinToggle = onPinToggle,
                    onMovePinned = onMovePinned,
                    onDismiss = { overflowOpen = false },
                )
                if (showMuteToggle) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (muted) {
                                        R.string.chat_row_action_unmute
                                    } else {
                                        R.string.chat_row_action_mute
                                    },
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (muted) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            overflowOpen = false
                            onMuteToggle()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_list_action_add_to_folder)) },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    onClick = {
                        overflowOpen = false
                        onAddToFolder()
                    },
                )
                if (allVisibleSelected) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_list_deselect_all)) },
                        onClick = {
                            overflowOpen = false
                            onDeselectAll()
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_list_select_all)) },
                        onClick = {
                            overflowOpen = false
                            onSelectAll()
                        },
                    )
                }
            }
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun PinnedSelectionMenuItems(
    showPinToggle: Boolean,
    pinned: Boolean,
    showMovePinnedUp: Boolean,
    showMovePinnedDown: Boolean,
    onPinToggle: () -> Unit,
    onMovePinned: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (showPinToggle) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (pinned) R.string.chat_row_action_unpin else R.string.chat_row_action_pin,
                    ),
                )
            },
            leadingIcon = {
                Icon(
                    if (pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                    contentDescription = null,
                )
            },
            onClick = {
                onDismiss()
                onPinToggle()
            },
        )
    }
    if (showMovePinnedUp) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_row_action_move_up)) },
            leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
            onClick = {
                onDismiss()
                onMovePinned(-1)
            },
        )
    }
    if (showMovePinnedDown) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_row_action_move_down)) },
            leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
            onClick = {
                onDismiss()
                onMovePinned(1)
            },
        )
    }
}
