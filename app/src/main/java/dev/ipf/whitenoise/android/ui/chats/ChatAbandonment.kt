package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.abandonChatFromList
import dev.ipf.whitenoise.android.ui.group.GroupAbandonmentDialog
import dev.ipf.whitenoise.android.ui.group.GroupAbandonmentStatus
import dev.ipf.whitenoise.android.ui.group.GroupAbandonmentTarget

/**
 * Whether the chat list offers MDK's forget for this row (#3310). A read-only invite declines instead, and a chat
 * this account already left or no longer holds (left, removed, disbanding or with a queued leave) has nothing to
 * abandon, so neither gets the action. The native row policy must also allow a local removal at all.
 */
internal fun ChatListItem.offersLocalAbandonment(activeAccountIdHex: String?): Boolean =
    !group.pendingConfirmation && actions?.canDeleteLocal != false && !removedFromGroup(activeAccountIdHex)

/** The row menu's abandonment entry, where [isDm] picks the direct-chat or group wording of the same forget. */
internal class ChatAbandonMenuAction(
    val isDm: Boolean,
    val onClick: () -> Unit,
)

/** One reviewed row: the owner that captured it and the identity its confirmation names, both fixed at capture. */
internal class PendingChatAbandonment private constructor(
    val owner: PendingLocalChatDelete,
    val target: GroupAbandonmentTarget,
) {
    companion object {
        /** Captures the row, its account/runtime owner and its displayed title when the menu action is chosen. */
        fun capture(
            item: ChatListItem,
            controller: ChatsController,
            appState: WhiteNoiseAppState,
            titleCopy: GroupTitleCopy,
        ): PendingChatAbandonment? {
            val account = controller.accountRef ?: return null
            val accountName = appState.activeAccount?.accountIdHex?.let(appState::accountDisplayNameCached) ?: account
            return PendingChatAbandonment(
                owner = PendingLocalChatDelete.capture(listOf(item), controller, appState),
                target =
                    GroupAbandonmentTarget(
                        accountRef = account,
                        accountName = accountName,
                        groupIdHex = item.group.groupIdHex,
                        conversationTitle = chatListItemDisplayTitle(item, appState, titleCopy),
                        isDm = item.isDm(),
                    ),
            )
        }
    }
}

/**
 * Chat-list host for the shared abandonment confirmation. Confirm closes the dialog before the journaled forget
 * starts, so a repeated tap cannot submit twice, and a stale account, runtime or bind submits nothing. The row
 * disappears optimistically, and a failure restores it with the same readable notice and retry as a local delete.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatAbandonmentHost(
    request: PendingChatAbandonment,
    appState: WhiteNoiseAppState,
    controller: ChatsController,
    busyChats: MutableSet<String>,
    onDismiss: () -> Unit,
) {
    val groupId = request.target.groupIdHex
    GroupAbandonmentDialog(
        target = request.target,
        status = GroupAbandonmentStatus.Review,
        onConfirm = {
            onDismiss()
            if (request.owner.isCurrent(appState, controller) && busyChats.add(groupId)) {
                appState.launchMutation {
                    try {
                        controller.abandonChatFromList(groupId, request.target.isDm)
                    } finally {
                        busyChats.remove(groupId)
                    }
                }
            }
        },
        onDismiss = onDismiss,
    )
}
