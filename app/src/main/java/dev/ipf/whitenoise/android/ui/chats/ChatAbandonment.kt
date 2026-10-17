package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.abandonChatFromList
import dev.ipf.whitenoise.android.state.isNonMember
import dev.ipf.whitenoise.android.state.offersLocalAbandonment
import dev.ipf.whitenoise.android.ui.chats.newchat.reviewMemberPublicName
import dev.ipf.whitenoise.android.ui.group.GroupAbandonmentDialog
import dev.ipf.whitenoise.android.ui.group.GroupAbandonmentStatus
import dev.ipf.whitenoise.android.ui.group.GroupAbandonmentTarget

/**
 * Whether the chat list offers MDK's forget for this row (#3310), by the same rule as Group Details: not for a
 * read-only invite, and not once MDK confirms the account left, was removed or the group is disbanded, read from the
 * group or its row. A queued leave or a converging disband still offers it, since either can stay unfinished. The
 * native row policy must also allow a local removal at all.
 */
internal fun ChatListItem.offersLocalAbandonment(): Boolean {
    val row = projection
    val rowConfirmsNonMember =
        row?.selfMembership?.isNonMember() == true || row?.lifecycleState == GroupLifecycleStateFfi.DISBANDED
    return group.offersLocalAbandonment() && !rowConfirmsNonMember && actions?.canDeleteLocal != false
}

/** A selection offers bulk abandonment only when it is not empty and every selected chat offers it. */
internal fun List<ChatListItem>.offerLocalAbandonment(): Boolean = isNotEmpty() && all { it.offersLocalAbandonment() }

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
            return PendingChatAbandonment(
                owner = PendingLocalChatDelete.capture(listOf(item), controller, appState),
                target =
                    GroupAbandonmentTarget(
                        accountRef = account,
                        accountName = abandonmentAccountName(appState, account),
                        groupIdHex = item.group.groupIdHex,
                        conversationTitle = chatListItemDisplayTitle(item, appState, titleCopy),
                        isDm = item.isDm(),
                    ),
            )
        }
    }
}

/**
 * The acting account a confirmation names, resolved from the captured [account] reference rather than whichever
 * account is active now: its public profile name, as the group-action identity shows it (#3282), else its cached
 * display name, else the reference itself.
 */
internal fun abandonmentAccountName(
    appState: WhiteNoiseAppState,
    account: String,
): String {
    val accountIdHex = appState.accounts.firstOrNull { it.label == account }?.accountIdHex ?: return account
    // Requests a missing profile and observes its arrival, so a review composed before it loads updates in place.
    val profile = appState.userProfile(accountIdHex)
    return reviewMemberPublicName(profile?.name)
        ?: reviewMemberPublicName(profile?.displayName)
        ?: appState.accountDisplayNameCached(accountIdHex)
}

/**
 * The chat-list bulk abandonment's batch steps (#3310). There is no handover to prepare or choose, and each reviewed
 * chat goes through the same journaled forget as the row action, in abandonment wording. The batch dialog reports
 * every outcome and owns the one Retry for unfinished chats, so no step posts its own success or failure notice.
 */
internal fun abandonmentDepartureCallbacks(
    controller: ChatsController,
    onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
): ChatDepartureCallbacks =
    ChatDepartureCallbacks(
        prepare = { emptyList() },
        choose = { _, _ -> null },
        remove = { target, _ -> controller.abandonChatFromList(target.groupId, target.isDm, reportedByBatch = true) },
        onProgress = onProgress,
    )

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
