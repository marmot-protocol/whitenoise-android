package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListRowFfi
import kotlinx.coroutines.launch

/**
 * Whether a chat's row and the group record cached for it disagree on the invitation being answered. The list
 * shows such a chat as still being checked, because neither snapshot alone can establish consent. A chat with no
 * cached record has nothing to disagree with.
 */
internal fun invitationSettlementDisagrees(
    rowPendingConfirmation: Boolean,
    cachedPendingConfirmation: Boolean?,
): Boolean = cachedPendingConfirmation != null && cachedPendingConfirmation != rowPendingConfirmation

/**
 * Re-reads a chat's group from MDK when its row and its cached record disagree on the invitation. When another of
 * the account's devices answers an invitation, MDK moves the row here, and the record this list cached may not be
 * repeated on the record stream, so without this read the chat stays shown as being checked until it is opened.
 * The read is MDK's own answer, applied to the row and the record together. It is dropped when the list was bound
 * again meanwhile, or when the record it set out to replace was itself replaced, because then it is the older one.
 */
internal fun ChatsController.settleInvitationFromMdk(row: ChatListRowFfi) {
    val account = accountRef ?: return
    val cached = groupRecordsById[row.groupIdHex]
    if (!invitationSettlementDisagrees(row.pendingConfirmation, cached?.pendingConfirmation)) return
    val epoch = bindEpoch
    recomputeScope.launch {
        runCatchingCancellable { appState.marmotIo { groupDetails(account, row.groupIdHex) } }
            .onSuccess { details ->
                val unchanged = groupRecordsById[row.groupIdHex] === cached
                if (isActiveBindEpoch(epoch) && accountRef == account && unchanged) {
                    applyProfileGroupDetails(account, details)
                }
            }
    }
}
