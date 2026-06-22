package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountUnreadTest {
    @Test
    fun accountUnreadCount_sumsDurableChatListRows() {
        val rows =
            listOf(
                row(groupId = "group-a", unreadCount = 2uL),
                row(groupId = "group-b", unreadCount = 0uL),
                row(groupId = "group-c", unreadCount = 5uL, archived = true),
            )

        assertEquals(2uL, accountUnreadCount(rows))
    }

    @Test
    fun accountUnreadCount_emptyRowsIsZero() {
        assertEquals(0uL, accountUnreadCount(emptyList()))
    }

    @Test
    fun accountUnreadCountFromItems_excludesRemovedGroupUnread() {
        // group-b's roster proves the active account is gone, so its 5 unread
        // are gated to zero in the projected item and must not reach the total
        // — only group-a's 2 count (issue #625).
        val me = "me-acc"
        val items =
            listOf(
                chatListItemFromProjection(
                    row(groupId = "group-a", unreadCount = 2uL),
                    activeAccountIdHex = me,
                    members = listOf(member(me), member("peer")),
                ),
                chatListItemFromProjection(
                    row(groupId = "group-b", unreadCount = 5uL),
                    activeAccountIdHex = me,
                    members = listOf(member("peer")),
                ),
            )

        assertEquals(2uL, accountUnreadCountFromItems(items))
    }

    @Test
    fun membershipAwareAccountUnreadCount_excludesRemovedGroupAndOnlyFetchesUnreadGroups() =
        runBlocking {
            // group-removed has unread but the roster proves the account is gone
            // → excluded. group-member has unread and the account is still in
            // → counted. group-quiet (0 unread) and group-archived must never
            // trigger a roster fetch (cost bound) and never contribute.
            val me = "me-acc"
            val rows =
                listOf(
                    row(groupId = "group-member", unreadCount = 3uL),
                    row(groupId = "group-removed", unreadCount = 5uL),
                    row(groupId = "group-quiet", unreadCount = 0uL),
                    row(groupId = "group-archived", unreadCount = 9uL, archived = true),
                )
            val fetched = mutableListOf<String>()
            val rosters =
                mapOf(
                    "group-member" to listOf(member(me), member("peer")),
                    "group-removed" to listOf(member("peer")),
                )

            val total =
                membershipAwareAccountUnreadCount(rows, activeAccountIdHex = me) { groupIdHex ->
                    fetched += groupIdHex
                    rosters[groupIdHex]
                }

            assertEquals(3uL, total)
            // Only the two genuinely-unread, unarchived groups were resolved.
            assertEquals(setOf("group-member", "group-removed"), fetched.toSet())
        }

    @Test
    fun membershipAwareAccountUnreadCount_nullRosterPreservesCount() =
        runBlocking {
            // Roster couldn't be resolved (fetch failed / unknown): uncertainty
            // must NOT zero the count — only a definitively self-excluded roster
            // does, matching activeAccountRemovedFromRoster.
            val rows = listOf(row(groupId = "g1", unreadCount = 4uL))

            val total = membershipAwareAccountUnreadCount(rows, activeAccountIdHex = "me-acc") { null }

            assertEquals(4uL, total)
        }

    @Test
    fun membershipAwareAccountUnreadCount_blankActiveAccountNeverFetchesAndKeepsCount() =
        runBlocking {
            // No known active account → we can't prove removal for any group,
            // so the fold degrades to the plain archived-excluded sum without
            // touching the roster resolver.
            val rows =
                listOf(
                    row(groupId = "g1", unreadCount = 2uL),
                    row(groupId = "g2", unreadCount = 6uL),
                    row(groupId = "g3", unreadCount = 1uL, archived = true),
                )
            var fetches = 0

            val total =
                membershipAwareAccountUnreadCount(rows, activeAccountIdHex = "  ") {
                    fetches += 1
                    null
                }

            assertEquals(8uL, total)
            assertEquals(0, fetches)
        }

    private fun member(accountIdHex: String) =
        AppGroupMemberRecordFfi(
            memberIdHex = accountIdHex,
            account = accountIdHex,
            local = true,
        )

    private fun row(
        groupId: String,
        unreadCount: ULong,
        archived: Boolean = false,
    ) = ChatListRowFfi(
        groupIdHex = groupId,
        archived = archived,
        pendingConfirmation = false,
        title = "Group $groupId",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        updatedAt = 0uL,
    )
}
