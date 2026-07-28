package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
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
        val current =
            row(
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = "read-200",
            )
        val incoming =
            row(
                lastMessageAt = 100uL,
                unreadCount = 3uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = "read-100",
            )

        assertNull(mergeMarkReadChatListRow(current, incoming))
    }

    @Test
    fun preservesCompleteReadWatermarkWhenIncomingIdMissing() {
        val current =
            row(
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idB,
            )
        val incoming =
            row(
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = null,
            )

        val merged = requireNotNull(mergeMarkReadChatListRow(current, incoming))
        assertEquals(100uL, merged.lastReadTimelineAt)
        assertEquals(idB, merged.lastReadMessageIdHex)
    }

    @Test
    fun preservesCurrentLastMessageWhenIncomingLastMessageNull() {
        val current =
            row(
                messageId = idB,
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = "read-50",
            )
        val incoming =
            row(
                messageId = idB,
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idB,
                includeLastMessage = false,
            )

        val merged = requireNotNull(mergeMarkReadChatListRow(current, incoming))
        assertEquals(idB, merged.lastMessage?.messageIdHex)
        assertEquals(idB, merged.lastReadMessageIdHex)
        assertEquals(100uL, merged.lastReadTimelineAt)
    }

    @Test
    fun rejectsEqualTimestampLowerReadMessageIdWatermark() {
        val current =
            row(
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idB,
            )
        val incoming =
            row(
                lastMessageAt = 100uL,
                unreadCount = 3uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idA,
            )

        assertNull(mergeMarkReadChatListRow(current, incoming))
    }

    @Test
    fun preservesEqualTimestampHigherLastMessageWhileAdvancingReadPointer() {
        val current =
            row(
                messageId = idB,
                lastMessageAt = 100uL,
                unreadCount = 1uL,
                lastReadTimelineAt = 50uL,
            )
        val incoming =
            row(
                messageId = idA,
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idB,
            )

        val merged = requireNotNull(mergeMarkReadChatListRow(current, incoming))
        assertEquals(idB, merged.lastMessage?.messageIdHex)
        assertEquals(1uL, merged.unreadCount)
        assertEquals(idB, merged.lastReadMessageIdHex)
        assertEquals(100uL, merged.lastReadTimelineAt)
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

    private val idA = "a".repeat(64)
    private val idB = "b".repeat(64)

    private fun row(
        messageId: String = "msg",
        lastMessageAt: ULong,
        unreadCount: ULong,
        lastReadTimelineAt: ULong? = null,
        lastReadMessageIdHex: String? = null,
        includeLastMessage: Boolean = true,
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
            if (includeLastMessage) {
                ChatListMessagePreviewFfi(
                    messageIdHex = messageId,
                    sender = "sender",
                    senderDisplayName = "Sender",
                    plaintext = "hello",
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                    kind = 9uL,
                    timelineAt = lastMessageAt,
                    deleted = false,
                    attachmentKind = null,
                    attachmentCount = 0u,
                    deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                )
            } else {
                null
            },
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = messageId,
        lastReadMessageIdHex = lastReadMessageIdHex,
        lastReadTimelineAt = lastReadTimelineAt,
        conversationCreatedAt = 0uL,
        activitySortAt = 0uL,
        updatedAt = lastMessageAt,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.UNKNOWN,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )
}
