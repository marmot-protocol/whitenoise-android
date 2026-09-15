@file:Suppress("FunctionNaming") // Composable names follow the framework convention.

package dev.ipf.whitenoise.android.ui.chats

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAnchoredMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem

/** Row-anchored prototype commands retain the production action policy and existing confirmation callbacks. */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // Declarative native action availability.
@Composable
internal fun ChatContextMenu(
    hasUnread: Boolean,
    canMarkUnread: Boolean,
    archived: Boolean,
    muted: Boolean,
    pinned: Boolean,
    showPinToggle: Boolean,
    showMovePinnedUp: Boolean,
    showMovePinnedDown: Boolean,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onAddToFolder: () -> Unit,
    onArchiveToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onPinToggle: () -> Unit,
    onMovePinned: (Int) -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    expanded: Boolean = true,
    focusable: Boolean = true,
    modifier: Modifier = Modifier,
    canRunAction: () -> Boolean = { true },
) {
    val items =
        buildList {
            if (hasUnread) {
                add(
                    chatMenuItem(R.string.chat_row_action_mark_read, R.drawable.ic_check, "Read") {
                        onMarkRead()
                    },
                )
            } else if (canMarkUnread) {
                add(
                    chatMenuItem(R.string.chat_row_action_mark_unread, R.drawable.ic_mark_unread, "Unread") {
                        onMarkUnread()
                    },
                )
            }
            if (showPinToggle) {
                add(
                    chatMenuItem(
                        if (pinned) R.string.chat_row_action_unpin else R.string.chat_row_action_pin,
                        if (pinned) R.drawable.ic_unpin else R.drawable.ic_push_pin,
                        if (pinned) "Unpin" else "Pin",
                    ) { onPinToggle() },
                )
            }
            add(
                chatMenuItem(
                    if (muted) R.string.chat_row_action_unmute else R.string.chat_row_action_mute,
                    if (muted) R.drawable.ic_settings_notifications else R.drawable.ic_notifications_off,
                    if (muted) "Unmute" else "Mute",
                ) { onMuteToggle() },
            )
            add(
                chatMenuItem(
                    if (archived) R.string.chat_row_action_unarchive else R.string.chat_row_action_archive,
                    if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive,
                    if (archived) "Unarchive" else "Archive",
                ) { onArchiveToggle() },
            )
            add(chatMenuItem(R.string.delete, R.drawable.ic_delete, "Delete", destructive = true) { onDelete() })
            add(
                chatMenuItem(R.string.chat_list_action_add_to_folder, R.drawable.ic_folder, "Folder") {
                    onAddToFolder()
                },
            )
            add(chatMenuItem(R.string.select, R.drawable.ic_check, "Select") { onSelect() })
            if (showMovePinnedUp) {
                add(
                    chatMenuItem(R.string.chat_row_action_move_up, R.drawable.ic_arrow_up, "MoveUp") {
                        onMovePinned(-1)
                    },
                )
            }
            if (showMovePinnedDown) {
                add(
                    chatMenuItem(R.string.chat_row_action_move_down, R.drawable.ic_arrow_down, "MoveDown") {
                        onMovePinned(1)
                    },
                )
            }
        }
    WhiteNoiseAnchoredMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        items = items,
        modifier = modifier,
        anchorSpacing = 8.dp,
        focusable = focusable,
        canRunAction = canRunAction,
    )
}

/** Command labels stay localized and expose one stable action target to semantics and gesture tests. */
@Composable
private fun chatMenuItem(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
): WhiteNoiseMenuItem =
    WhiteNoiseMenuItem(
        label = stringResource(label),
        icon = icon,
        onClick = onClick,
        destructive = destructive,
        modifier = Modifier.testTag("chat.action.$tag"),
    )

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
