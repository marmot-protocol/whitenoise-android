package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
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

/** One outstanding native read for a row and its disagreeing cached group. */
internal class InvitationSettlementRead(
    val cached: AppGroupRecordFfi,
) {
    var superseded = false
}

/** Re-read only a disagreement. Duplicate snapshots share the read; a replacement waits for its own answer. */
internal fun ChatsController.settleInvitationFromMdk(
    row: ChatListRowFfi,
    read: suspend (String, String) -> AppGroupRecordFfi = { account, groupId ->
        appState.marmotIo { groupDetails(account, groupId).group }
    },
) {
    val account = accountRef ?: return
    val key = chatRowKey(row.groupIdHex)
    val cached =
        groupRecordsById[row.groupIdHex]?.takeIf {
            invitationSettlementDisagrees(row.pendingConfirmation, it.pendingConfirmation)
        }
    val request = claimInvitationRead(key, cached) ?: return
    val epoch = bindEpoch
    recomputeScope.launch {
        try {
            val record = runCatchingCancellable { read(account, row.groupIdHex) }.getOrNull()
            val ownsRead = !request.superseded && isActiveBindEpoch(epoch) && accountRef == account
            val currentRow = chatRowsByGroup[key]
            val sameGroup = groupRecordsById[row.groupIdHex] == request.cached
            if (ownsRead && sameGroup && record?.settlesRow(row, currentRow) == true) {
                // The row stream already owns its newer archive, title and unread values. Only settle the record.
                foldGroup(record)
            }
        } finally {
            if (invitationReads.remove(key, request) && request.superseded && isActiveBindEpoch(epoch)) {
                chatRowsByGroup[key]?.let { settleInvitationFromMdk(it, read) }
            }
        }
    }
}

/** Both row projections must still describe the invitation state returned by MDK. */
private fun AppGroupRecordFfi.settlesRow(
    requested: ChatListRowFfi,
    current: ChatListRowFfi?,
): Boolean =
    groupIdHex == requested.groupIdHex &&
        pendingConfirmation == requested.pendingConfirmation &&
        pendingConfirmation == current?.pendingConfirmation

/** Keeps one reader per group and remembers whether a later stream update retired its evidence. */
private fun ChatsController.claimInvitationRead(
    key: String,
    cached: AppGroupRecordFfi?,
): InvitationSettlementRead? {
    val active = invitationReads[key]
    return if (active == null) {
        cached?.let { InvitationSettlementRead(it) }?.also { invitationReads[key] = it }
    } else {
        if (active.cached != cached) active.superseded = true
        null
    }
}
