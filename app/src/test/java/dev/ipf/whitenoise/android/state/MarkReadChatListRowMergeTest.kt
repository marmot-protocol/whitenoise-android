package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkReadChatListRowMergeTest {
    @Test
    fun replacesStaleUnreadWhenLastMessageMatches() {
        val current = row(lastMessageAt = 100uL, unreadCount = 3uL, lastReadTimelineAt = 50uL)
        val incoming =
            row(
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = "read-100",
            )

        assertEquals(incoming, mergeMarkReadChatListRow(current, incoming))
    }

    @Test
    fun rejectsSupersededReadWatermark() {
        val current = row(lastMessageAt = 100uL, unreadCount = 0uL, lastReadTimelineAt = 200uL)
        val incoming =
            row(
                lastMessageAt = 100uL,
                unreadCount = 3uL,
                lastReadTimelineAt = 100uL,
            )

        assertNull(mergeMarkReadChatListRow(current, incoming))
    }

    @Test
    fun preservesNewerLastMessageWhileAdvancingReadPointer() {
        val current =
            row(
                messageId = "msg-new",
                lastMessageAt = 200uL,
                unreadCount = 1uL,
                lastReadTimelineAt = 100uL,
            )
        val incoming =
            row(
                messageId = "msg-old",
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 150uL,
                lastReadMessageIdHex = "read-150",
            )

        val merged = requireNotNull(mergeMarkReadChatListRow(current, incoming))
        assertEquals("msg-new", merged.lastMessage?.messageIdHex)
        assertEquals(1uL, merged.unreadCount)
        assertEquals("read-150", merged.lastReadMessageIdHex)
        assertEquals(150uL, merged.lastReadTimelineAt)
    }

    private fun row(
        messageId: String = "msg",
        lastMessageAt: ULong,
        unreadCount: ULong,
        lastReadTimelineAt: ULong? = null,
        lastReadMessageIdHex: String? = null,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = "group",
        archived = false,
        pendingConfirmation = false,
        title = "Chat",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage =
            ChatListMessagePreviewFfi(
                messageIdHex = messageId,
                sender = "sender",
                senderDisplayName = "Sender",
                plaintext = "hello",
                contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                kind = 9uL,
                timelineAt = lastMessageAt,
                deleted = false,
            ),
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = messageId,
        lastReadMessageIdHex = lastReadMessageIdHex,
        lastReadTimelineAt = lastReadTimelineAt,
        updatedAt = lastMessageAt,
    )
}
