package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.PresentedChatRowFfi
import java.util.Locale

// Forward and share targets beyond the retained chat-list window (#2618).
//
// MarmotKit 0.10.0 serves the chat list as a bounded window, so ChatsController.forwardTargets() only
// sees the rows that window retains. A picker that must offer every conversation reads the complete
// presented list from MDK once, on demand, and merges it under the retained rows: MDK stays the owner
// of the rows, the controller's window stays the fresh source for anything it retains, and the
// account-wide read is held only for the lifetime of the open picker.

/**
 * Every eligible forward target MDK presents for the bound account that the retained window does not
 * already hold, projected with this controller's caches; null when no account is bound, the read is
 * unavailable or failed, or the binding changed while the read was in flight.
 */
internal suspend fun ChatsController.loadAccountWideForwardTargets(): List<ChatListItem>? {
    val account = accountRef
    val read = liveSubscriptions.presentedChatList
    if (account == null || read == null) return null
    val epoch = bindEpoch
    return runCatchingCancellable { read(account, true) }
        .getOrNull()
        ?.takeIf { isActiveBindEpoch(epoch) && accountRef == account }
        ?.let { presented -> eligibleTargetsBeyondWindow(presented) }
}

/** Projects the presented rows the window does not retain and keeps those a forward can be sent into. */
private fun ChatsController.eligibleTargetsBeyondWindow(presented: List<PresentedChatRowFfi>): List<ChatListItem> {
    val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
    return presented
        .filterNot { containsGroup(it.row.groupIdHex) }
        .map { projectPresentedRow(it, activeAccountIdHex) }
        .filter { isEligibleForwardTarget(it, activeAccountIdHex) }
}

/**
 * [retained] targets first, then every [accountWide] target the window does not retain, in the chat
 * list's recent-first order. Retained rows win because they are live; an account-wide row is a one-time
 * read. A null [accountWide] (not yet loaded, or unavailable) leaves the retained targets untouched.
 */
internal fun mergeForwardTargets(
    retained: List<ChatListItem>,
    accountWide: List<ChatListItem>?,
): List<ChatListItem> {
    if (accountWide.isNullOrEmpty()) return retained
    val retainedIds = retained.mapTo(hashSetOf()) { it.group.groupIdHex.lowercase(Locale.ROOT) }
    val extra = accountWide.filterNot { it.group.groupIdHex.lowercase(Locale.ROOT) in retainedIds }
    return if (extra.isEmpty()) retained else sortChatListItems(retained + extra)
}
