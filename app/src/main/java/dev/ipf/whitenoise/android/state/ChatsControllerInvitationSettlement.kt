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
 * The read is MDK's own answer, applied to the row and the record together, and a result for an account that is
 * no longer bound is dropped.
 */
internal fun ChatsController.settleInvitationFromMdk(row: ChatListRowFfi) {
    val account = accountRef ?: return
    val cached = groupRecordsById[row.groupIdHex]?.pendingConfirmation
    if (!invitationSettlementDisagrees(row.pendingConfirmation, cached)) return
    recomputeScope.launch {
        runCatchingCancellable { appState.marmotIo { groupDetails(account, row.groupIdHex) } }
            .onSuccess { details -> if (accountRef == account) applyProfileGroupDetails(account, details) }
    }
}
