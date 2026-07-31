package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

/** Single-chat actions shown by a stationary long press outside selection mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatActionSheet(
    hasUnread: Boolean,
    canMarkUnread: Boolean,
    archived: Boolean,
    muted: Boolean,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onAddToFolder: () -> Unit,
    onArchiveToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun runAction(action: () -> Unit) {
        onDismiss()
        action()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (hasUnread) {
                ChatActionButton(
                    label = stringResource(R.string.chat_row_action_mark_read),
                    icon = Icons.Default.MarkChatRead,
                    onClick = { runAction(onMarkRead) },
                )
            } else if (canMarkUnread) {
                ChatActionButton(
                    label = stringResource(R.string.chat_row_action_mark_unread),
                    icon = Icons.Default.MarkChatUnread,
                    onClick = { runAction(onMarkUnread) },
                )
            }
            ChatActionButton(
                label = stringResource(R.string.chat_list_action_add_to_folder),
                icon = Icons.Default.Folder,
                onClick = { runAction(onAddToFolder) },
            )
            ChatActionButton(
                label =
                    stringResource(
                        if (archived) R.string.chat_row_action_unarchive else R.string.chat_row_action_archive,
                    ),
                icon = if (archived) Icons.Default.Unarchive else Icons.Default.Archive,
                onClick = { runAction(onArchiveToggle) },
            )
            ChatActionButton(
                label =
                    stringResource(
                        if (muted) R.string.chat_row_action_unmute else R.string.chat_row_action_mute,
                    ),
                icon = if (muted) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                onClick = { runAction(onMuteToggle) },
            )
            ChatActionButton(
                label = stringResource(R.string.select),
                icon = Icons.Default.CheckCircle,
                onClick = { runAction(onSelect) },
            )
            ChatActionButton(
                label = stringResource(R.string.delete),
                icon = Icons.Default.Delete,
                destructive = true,
                onClick = { runAction(onDelete) },
            )
        }
    }
}

/** Shared confirmation reached by both single-chat and bulk delete actions. */
@Composable
internal fun ChatDeleteConfirmationDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.delete_group_confirm),
        message = pluralStringResource(R.plurals.chat_list_bulk_delete_confirm, count, count),
        confirmLabel = stringResource(R.string.delete_group_confirm),
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun ChatActionButton(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors =
            ButtonDefaults.textButtonColors(
                contentColor =
                    if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
