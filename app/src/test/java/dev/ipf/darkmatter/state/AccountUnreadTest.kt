package dev.ipf.darkmatter.state

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

        assertEquals(7uL, accountUnreadCount(rows))
    }

    @Test
    fun accountUnreadCount_emptyRowsIsZero() {
        assertEquals(0uL, accountUnreadCount(emptyList()))
    }

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
