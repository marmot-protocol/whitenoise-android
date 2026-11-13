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
import dev.ipf.marmotkit.ChatListRowActionsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAnchoredMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.group.groupAbandonmentActionLabel

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
    actions: ChatListRowActionsFfi? = null,
    onLeaveAndDelete: (() -> Unit)? = null,
    onExcludeFromFolder: (() -> Unit)? = null,
    onAbandon: ChatAbandonMenuAction? = null,
    historyDeleteBlocked: Boolean = false,
) {
    val items =
        buildList {
            if (hasUnread && actions?.canMarkRead != false) {
                add(
                    chatMenuItem(
                        R.string.chat_row_action_mark_read,
                        chatReadIcon(markRead = true),
                        "Read",
                    ) {
                        onMarkRead()
                    },
                )
            } else if (canMarkUnread && actions?.canMarkUnread != false) {
                add(
                    chatMenuItem(
                        R.string.chat_row_action_mark_unread,
                        chatReadIcon(markRead = false),
                        "Unread",
                    ) {
                        onMarkUnread()
                    },
                )
            }
            if (showPinToggle && if (pinned) actions?.canUnpin != false else actions?.canPin != false) {
                add(
                    chatMenuItem(
                        if (pinned) R.string.chat_row_action_unpin else R.string.chat_row_action_pin,
                        if (pinned) R.drawable.ic_unpin else R.drawable.ic_push_pin,
                        if (pinned) "Unpin" else "Pin",
                    ) { onPinToggle() },
                )
            }
            if (if (muted) actions?.canUnmute != false else actions?.canMute != false) {
                add(
                    chatMenuItem(
                        if (muted) R.string.chat_row_action_unmute else R.string.chat_row_action_mute,
                        if (muted) R.drawable.ic_settings_notifications else R.drawable.ic_notifications_off,
                        if (muted) "Unmute" else "Mute",
                    ) { onMuteToggle() },
                )
            }
            if (if (archived) actions?.canRestore != false else actions?.canArchive != false) {
                add(
                    chatMenuItem(
                        if (archived) R.string.chat_row_action_unarchive else R.string.chat_row_action_archive,
                        if (archived) R.drawable.ic_unarchive else R.drawable.ic_archive,
                        if (archived) "Unarchive" else "Archive",
                    ) { onArchiveToggle() },
                )
            }
            if (onLeaveAndDelete != null && actions?.canStartLeave == true && actions.canDeleteLocal) {
                add(
                    chatMenuItem(
                        R.string.leave_and_delete,
                        R.drawable.ic_delete,
                        "LeaveAndDelete",
                        destructive = true,
                        explanation =
                            chatsExplanation(
                                R.string.leave_and_delete,
                                R.string.explanation_chat_actions_leave_and_delete,
                            ),
                        beforeShow = onDismiss,
                    ) {
                        onLeaveAndDelete()
                    },
                )
            }
            // A chat the account still receives never offers a history-only delete: it returns with the next
            // message, so it is offered only once nothing more can arrive. [historyDeleteBlocked] says so even where
            // no forget is offered (groups), and a passed [onAbandon] blocks it on its own.
            if (onAbandon == null && !historyDeleteBlocked && actions?.canDeleteLocal != false) {
                add(
                    chatMenuItem(
                        R.string.delete_from_device,
                        R.drawable.ic_delete,
                        "Delete",
                        destructive = true,
                        explanation =
                            chatsExplanation(
                                R.string.delete_from_device,
                                R.string.explanation_chat_actions_delete,
                            ),
                        beforeShow = onDismiss,
                    ) {
                        onDelete()
                    },
                )
            }
            // #3310: local abandonment stays distinct from leaving. The chat list passes it for direct chats only;
            // a group is reset from Group Details.
            if (onAbandon != null) {
                add(
                    chatMenuItem(
                        groupAbandonmentActionLabel(onAbandon.isDm),
                        R.drawable.ic_person_remove,
                        "Abandon",
                        destructive = true,
                        explanation =
                            chatsExplanation(
                                groupAbandonmentActionLabel(onAbandon.isDm),
                                if (onAbandon.isDm) {
                                    R.string.explanation_chat_actions_abandon_dm
                                } else {
                                    R.string.explanation_chat_actions_abandon_group
                                },
                            ),
                        beforeShow = onDismiss,
                    ) { onAbandon.onClick() },
                )
            }
            add(
                chatMenuItem(R.string.chat_list_action_add_to_folder, R.drawable.ic_folder, "Folder") {
                    onAddToFolder()
                },
            )
            if (onExcludeFromFolder != null) {
                add(
                    chatMenuItem(R.string.folder_exclude_chat, R.drawable.ic_folder, "ExcludeFolder") {
                        onExcludeFromFolder()
                    },
                )
            }
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
    explanation: Explanation? = null,
    beforeShow: () -> Unit = {},
    onClick: () -> Unit,
): WhiteNoiseMenuItem =
    WhiteNoiseMenuItem(
        label = stringResource(label),
        icon = icon,
        onClick = onClick,
        destructive = destructive,
        modifier = Modifier.testTag("chat.action.$tag").explainOnLongPress(explanation, beforeShow = beforeShow),
    )

/** Shared confirmation reached by both single-chat and bulk delete actions. */
@Composable
internal fun ChatDeleteConfirmationDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    identity: (@Composable () -> Unit)? = null,
    confirmEnabled: Boolean = true,
) {
    ConfirmDialog(
        title = stringResource(R.string.delete_from_device),
        identity = identity,
        confirmEnabled = confirmEnabled,
        message =
            if (count == 1) {
                stringResource(R.string.delete_local_does_not_leave)
            } else {
                pluralStringResource(R.plurals.chat_list_bulk_delete_confirm, count, count)
            },
        confirmLabel = stringResource(R.string.delete_group_confirm),
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Departure is a separate, explicit choice from device-local history deletion. */
@Composable
internal fun ChatLeaveAndDeleteConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.leave_and_delete),
        message = stringResource(R.string.leave_and_delete_message),
        confirmLabel = stringResource(R.string.leave_and_delete),
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
