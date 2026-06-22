package dev.ipf.darkmatter.state

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
 * Aggregate unread messages from projected chat items, applying the
 * removed-group suppression ([ChatListItem.effectiveUnreadCount]) for the
 * active account so a group the user has left/been removed from no longer
 * contributes its frozen unread total. Same archived exclusion as the raw-row
 * overload. Used when the controller already holds projected items and the
 * suppressed count must flow to every consumer of the per-account aggregate —
 * notably the cross-account unread dot (#625).
 */
internal fun accountUnreadCount(
    items: Iterable<ChatListItem>,
    activeAccountIdHex: String?,
): ULong =
    items.fold(0uL) { total, item ->
        if (item.group.archived) total else total + item.effectiveUnreadCount(activeAccountIdHex)
    }
