package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
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
    fun accountUnreadCount_suppressesRowsWhoseLoadedRosterOmitsAccount() {
        val account = "account-b"
        val rows =
            listOf(
                row(groupId = "actionable", unreadCount = 2uL),
                row(groupId = "removed", unreadCount = 7uL),
            )

        val count =
            accountUnreadCount(
                rows,
                activeAccountIdHex = account,
                membersByGroupId =
                    mapOf(
                        "actionable" to listOf(member(account), member("peer-a")),
                        "removed" to listOf(member("peer-b")),
                    ),
            )

        assertEquals(2uL, count)
    }

    @Test
    fun accountUnreadCount_suppressesEmptyLoadedRoster() {
        val rows = listOf(row(groupId = "left-as-sole-member", unreadCount = 4uL))

        val count =
            accountUnreadCount(
                rows,
                activeAccountIdHex = "account-b",
                membersByGroupId = mapOf("left-as-sole-member" to emptyList()),
            )

        assertEquals(0uL, count)
    }

    @Test
    fun accountUnreadCount_preservesUnreadWhenRosterWasNotLoaded() {
        val rows = listOf(row(groupId = "unknown-members", unreadCount = 4uL))

        val count =
            accountUnreadCount(
                rows,
                activeAccountIdHex = "account-b",
                membersByGroupId = emptyMap(),
            )

        assertEquals(4uL, count)
    }

    @Test
    fun accountUnreadCount_suppressionAwareOverloadStillExcludesArchivedRows() {
        val rows = listOf(row(groupId = "archived", unreadCount = 9uL, archived = true))

        val count =
            accountUnreadCount(
                rows,
                activeAccountIdHex = "account-b",
                membersByGroupId = mapOf("archived" to listOf(member("account-b"))),
            )

        assertEquals(0uL, count)
    }

    private fun row(
        groupId: String,
        unreadCount: ULong,
        archived: Boolean = false,
    ) = ChatListRowFfi(
        unreadMentionCount = 0uL,
        unreadMention = false,
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

    private fun member(accountIdHex: String) =
        AppGroupMemberRecordFfi(
            memberIdHex = accountIdHex,
            account = accountIdHex,
            local = false,
        )
}
