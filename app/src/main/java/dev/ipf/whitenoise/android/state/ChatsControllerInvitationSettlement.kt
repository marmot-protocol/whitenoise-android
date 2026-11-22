package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import kotlinx.coroutines.launch

/**
 * Whether a chat's row and the group record cached for it disagree on the invitation being answered. The list
 * shows such a chat as still being checked, because neither snapshot alone can establish consent. A pending row
 * with no cached record is shown as checked too, so it needs the same read: a chat another of the account's
 * devices adds can arrive in the list without its record ever reaching the chats stream.
 */
internal fun invitationSettlementDisagrees(
    rowPendingConfirmation: Boolean,
    cachedPendingConfirmation: Boolean?,
): Boolean = cachedPendingConfirmation?.let { it != rowPendingConfirmation } ?: rowPendingConfirmation

/** One outstanding native read for a row and the cached group it disagreed with, null when none was cached. */
internal class InvitationSettlementRead(
    val cached: AppGroupRecordFfi?,
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
    val cached = groupRecordsById[row.groupIdHex]
    // Before the group snapshot arrives every row lacks its record, and the snapshot settles those rows itself.
    val disagrees =
        (cached != null || groupRecordsLoaded) &&
            invitationSettlementDisagrees(row.pendingConfirmation, cached?.pendingConfirmation)
    val request = claimInvitationRead(key, cached, disagrees) ?: return
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

/**
 * Keeps one reader per group and remembers whether a later stream update retired its evidence: the row and record
 * now agree, or the cached record changed.
 */
private fun ChatsController.claimInvitationRead(
    key: String,
    cached: AppGroupRecordFfi?,
    disagrees: Boolean,
): InvitationSettlementRead? {
    val active = invitationReads[key]
    return if (active == null) {
        InvitationSettlementRead(cached).takeIf { disagrees }?.also { invitationReads[key] = it }
    } else {
        if (!disagrees || active.cached != cached) active.superseded = true
        null
    }
}
