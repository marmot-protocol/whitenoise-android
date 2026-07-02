package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi

/**
 * Aggregate unread messages for an account from durable chat-list rows.
 * Matches Marmot's `account_unread_total` projection: archived chats are
 * excluded. Use one of the suppression-aware overloads when a loaded member
 * roster is available, so removed groups do not contribute frozen unread totals.
 */
internal fun accountUnreadCount(rows: Iterable<ChatListRowFfi>): ULong =
    rows.fold(0uL) { total, row ->
        if (row.archived) total else total + row.unreadCount
    }

/**
 * Aggregate unread messages from durable chat-list rows, applying removed-group
 * suppression for rows whose member roster was successfully loaded for this
 * account. A missing [membersByGroupId] entry means "unknown" and preserves the
 * raw unread count; a present roster that omits [activeAccountIdHex] (including
 * an empty, successfully-loaded post-leave roster) suppresses the row to zero.
 */
internal fun accountUnreadCount(
    rows: Iterable<ChatListRowFfi>,
    activeAccountIdHex: String?,
    membersByGroupId: Map<String, List<AppGroupMemberRecordFfi>>,
): ULong =
    rows.fold(0uL) { total, row ->
        if (row.archived) {
            total
        } else {
            val members = membersByGroupId[row.groupIdHex]
            val removed = accountMissingFromLoadedRoster(activeAccountIdHex, members)
            total +
                chatListItemFromProjection(
                    row = row,
                    activeAccountIdHex = activeAccountIdHex,
                    members = members,
                    removed = removed,
                ).effectiveUnreadCount(activeAccountIdHex)
        }
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

/**
 * Whether [accountRef]'s avatar should show the unread dot (#805). The dot is a
 * per-account property: it reads *that account's own* aggregate from
 * [countsByAccountRef] and never "some other account has unread". Every avatar
 * in the chat-list top bar (active + secondary) and the account switcher shares
 * this single decision so the paths can't drift apart again — the misrouting
 * bug was the active avatar checking a cross-account aggregate instead.
 */
internal fun accountShowsUnreadDot(
    accountRef: String?,
    countsByAccountRef: Map<String, ULong>,
): Boolean {
    val ref = accountRef?.takeIf { it.isNotBlank() } ?: return false
    return (countsByAccountRef[ref] ?: 0uL) > 0uL
}

private fun accountMissingFromLoadedRoster(
    activeAccountIdHex: String?,
    members: List<AppGroupMemberRecordFfi>?,
): Boolean {
    val active = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val loadedMembers = members ?: return false
    return loadedMembers.none { it.memberIdHex.equals(active, ignoreCase = true) }
}
