package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Unarchive
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
import androidx.compose.ui.res.stringResource
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
    showMuteToggle: Boolean,
    muted: Boolean,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkRead: () -> Unit,
    onMuteToggle: () -> Unit,
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
    TopAppBar(
        title = { Text(count.toString()) },
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
                        leadingIcon = { Icon(Icons.Default.NotificationsOff, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            onMuteToggle()
                        },
                    )
                }
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
