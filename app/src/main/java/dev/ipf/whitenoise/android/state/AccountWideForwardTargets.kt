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
//
// A profile's groups in common and its Add to Groups and Promote pickers read the same snapshot (#2983),
// held for as long as that profile surface is open.

/**
 * Every eligible forward target MDK presents for the bound account that the retained window does not
 * already hold, projected with this controller's caches; null when no account is bound, the read is
 * unavailable or failed, or the binding changed while the read was in flight.
 */
internal suspend fun ChatsController.loadAccountWideForwardTargets(): AccountWideForwardTargets? {
    val account = accountRef
    val read = liveSubscriptions.presentedChatList
    if (account == null || read == null) return null
    val epoch = bindEpoch
    return runCatchingCancellable { read(account, true) }
        .getOrNull()
        ?.takeIf { isActiveBindEpoch(epoch) && accountRef == account }
        ?.let { presented -> AccountWideForwardTargets(this, epoch, presented) }
}

/** Native rows owned only by an open picker; derived rows always use the controller's current roster cache. */
internal class AccountWideForwardTargets(
    private val controller: ChatsController,
    private val epoch: Long,
    private val presented: List<PresentedChatRowFfi>,
) {
    /** A successful read stops proving completeness when its controller is rebound or cleared. */
    val isCurrent: Boolean get() = controller.isActiveBindEpoch(epoch)

    /** Rejects expired bindings and prefers the current retained window over the account-wide snapshot. */
    fun items(): List<ChatListItem> {
        if (!isCurrent) return emptyList()
        val accountId = controller.boundAccountIdHex()
        return presented
            .filterNot { controller.containsGroup(it.row.groupIdHex) }
            .map { controller.projectPresentedRow(it, accountId) }
            .filter { isEligibleForwardTarget(it, accountId) }
    }

    /** Resolves only missing rosters requested from this exact account-owned presented snapshot. */
    suspend fun resolveMembers(groupIds: Set<String>) {
        resolveMembersBeyondWindow(groupIds)
    }

    /**
     * Resolves the requested rosters of chats the retained window does not hold, and returns those groups
     * that still have no roster afterwards, so a profile picker can offer a retry instead of loading forever.
     * Empty once the binding has expired: a late result is never reported against the next account.
     */
    suspend fun resolveMembersBeyondWindow(groupIds: Set<String>): Set<String> {
        if (!isCurrent) return emptySet()
        val requested =
            presented
                .filter { it.row.groupIdHex in groupIds }
                .filterNot { controller.containsGroup(it.row.groupIdHex) }
        controller.resolveForwardTargetMembers(requested)
        return requested.mapNotNullTo(linkedSetOf()) { row ->
            row.row.groupIdHex.takeIf { isCurrent && !controller.hasMemberSnapshot(it) }
        }
    }
}

/**
 * The chats beyond the retained window that one open profile surface reads for its group lookups (#2983):
 * the account-wide [snapshot], and the groups in it whose rosters could not be resolved.
 */
internal data class ProfileAccountWideGroups(
    val snapshot: AccountWideForwardTargets? = null,
    val failedGroupIds: Set<String> = emptySet(),
) {
    /** Reprojects the snapshot with the controller's current caches; null until a read has succeeded. */
    fun items(): List<ChatListItem>? = snapshot?.items()
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
