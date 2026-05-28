package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListSortingTest {
    @Test
    fun chatsWithoutMessagesSortAfterChatsWithMessages() {
        val withLatest = item("with-latest", latestAt = 25uL)
        val withoutLatest = item("without-latest", latestAt = null)

        val sorted = sortChatListItems(listOf(withoutLatest, withLatest))

        assertEquals(listOf("with-latest", "without-latest"), sorted.map { it.id })
    }

    @Test
    fun chatsWithoutMessagesCanSortBesideUnsignedLongMessageTimes() {
        val sorted = sortChatListItems(
            listOf(
                item("no-message", latestAt = null),
                item("newer", latestAt = ULong.MAX_VALUE),
                item("older", latestAt = 1uL),
            ),
        )

        assertEquals(listOf("newer", "older", "no-message"), sorted.map { it.id })
    }

    @Test
    fun pendingInvitesSortBeforeExistingChats() {
        val sorted = sortChatListItems(
            listOf(
                item("active-chat", latestAt = 50uL),
                item("pending-invite", latestAt = null, pending = true),
            ),
        )

        assertEquals(listOf("pending-invite", "active-chat"), sorted.map { it.id })
    }

    @Test
    fun projectedChatListRowCarriesTitlePreviewTimestampAndUnreadState() {
        val item = chatListItemFromProjection(
            row(
                groupId = "group-a",
                title = "Marmot Lab",
                preview = "projected latest",
                latestAt = 20uL,
                unreadCount = 3uL,
            ),
        )

        assertEquals("Marmot Lab", item.projectedTitle)
        assertEquals("projected latest", item.projectedPreviewText(empty = "empty"))
        assertEquals(20uL, item.latestAt)
        assertEquals(3uL, item.unreadCount)
        assertTrue(item.hasUnread)
    }

    private fun item(id: String, latestAt: ULong?, pending: Boolean = false): ChatListItem {
        return ChatListItem(
            group = group(id, pending = pending),
            latest = latestAt?.let { message(groupId = id, recordedAt = it) },
            otherMemberAccount = null,
            memberCount = 0,
            memberSnapshot = null,
        )
    }

    private fun row(
        groupId: String,
        title: String,
        preview: String,
        latestAt: ULong,
        unreadCount: ULong,
    ) = ChatListRowFfi(
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = title,
        groupName = "",
        avatar = null,
        lastMessage = ChatListMessagePreviewFfi(
            messageIdHex = "message-$groupId",
            sender = "sender",
            senderDisplayName = "Sender",
            plaintext = preview,
            kind = 9uL,
            timelineAt = latestAt,
            deleted = false,
        ),
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = "message-$groupId",
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        updatedAt = latestAt,
    )

    private fun group(id: String, pending: Boolean = false) = AppGroupRecordFfi(
        groupIdHex = id,
        endpoint = "endpoint-$id",
        name = "",
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$id",
        archived = false,
        pendingConfirmation = pending,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

    private fun message(
        groupId: String,
        recordedAt: ULong,
        plaintext: String = "hello",
        tags: List<MessageTagFfi> = emptyList(),
    ) = AppMessageRecordFfi(
        messageIdHex = "message-$groupId",
        direction = "received",
        groupIdHex = groupId,
        sender = "sender",
        plaintext = plaintext,
        kind = 9uL,
        tags = tags,
        recordedAt = recordedAt,
        receivedAt = recordedAt,
    )
}
