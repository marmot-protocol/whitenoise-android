package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi

/**
 * Aggregate unread messages for an account from durable chat-list rows.
 * Matches Marmot's `account_unread_total` projection: archived chats are
 * excluded. Used when the chat-list controller already holds the rows; prefer
 * [dev.ipf.marmotkit.Marmot.accountUnreadSummary] when refreshing every account
 * without loading each chat list.
 */
internal fun accountUnreadCount(rows: Iterable<ChatListRowFfi>): ULong =
    rows.fold(0uL) { total, row ->
        if (row.archived) total else total + row.unreadCount
    }

/**
 * Same archived-excluded fold as [accountUnreadCount], but over already-projected
 * [ChatListItem]s so the account total honors the per-row membership gate
 * ([chatListItemFromProjection] zeros the unread badge of a group the account was
 * removed from). Folding raw [ChatListRowFfi]s would still count those phantom
 * unreads since the engine never zeros them on self-removal (issue #625).
 */
internal fun accountUnreadCountFromItems(items: Iterable<ChatListItem>): ULong =
    items.fold(0uL) { acc, item ->
        if (item.group.archived) acc else acc + item.unreadCount
    }

/**
 * Membership-aware account unread total computed straight from durable engine
 * rows, for code paths that don't go through [ChatsController]'s projected
 * items — the all-account refresh ([dev.ipf.darkmatter.state.DarkMatterAppState.refreshAccountUnreadCounts])
 * and the per-notification hot path ([dev.ipf.darkmatter.state.DarkMatterAppState.refreshAccountUnreadCount]).
 *
 * The engine's `unread_summary`/`chat_list` rows never zero a removed group's
 * unread count (the engine projection is membership-blind; issue #625), so a
 * raw [accountUnreadCount] fold reintroduces the phantom unread badge for the
 * account-switcher / cross-account avatar dot after startup, an account-list
 * refresh, or a notification update — even when [ChatsController] has already
 * written the correct membership-aware total. Mirror the per-row gate here at
 * the source-of-truth fold so every downstream badge agrees.
 *
 * Cost is bounded: a roster is fetched ONLY for groups that actually carry
 * unread (the only rows that can contribute a phantom). A group at zero unread
 * never triggers a fetch, and an archived row is excluded before any fetch, so
 * the common case (no unread, or unread only in groups the account still
 * belongs to) costs the same as before plus one local-SQLite roster read per
 * genuinely-unread group.
 *
 * [resolveRoster] returns the group's current roster (a local read, e.g.
 * `groupMembers(accountRef, groupIdHex)`), or null when it can't be resolved.
 * Uncertainty preserves the count — only a roster that is definitively loaded
 * and proves the account absent zeros that group's contribution, matching
 * [activeAccountRemovedFromRoster] so the two paths can never disagree.
 *
 * [activeAccountIdHex] is the account whose membership we're checking. When it
 * is null/blank we can't prove removal for any group, so the fold degrades to
 * the plain [accountUnreadCount].
 */
internal suspend fun membershipAwareAccountUnreadCount(
    rows: Iterable<ChatListRowFfi>,
    activeAccountIdHex: String?,
    resolveRoster: suspend (groupIdHex: String) -> List<AppGroupMemberRecordFfi>?,
): ULong {
    val active = activeAccountIdHex?.takeIf { it.isNotBlank() }
    var total = 0uL
    for (row in rows) {
        if (row.archived || row.unreadCount == 0uL) continue
        if (active == null) {
            total += row.unreadCount
            continue
        }
        val roster = resolveRoster(row.groupIdHex)
        if (activeAccountRemovedFromRoster(roster, active)) continue
        total += row.unreadCount
    }
    return total
}
