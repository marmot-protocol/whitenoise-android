package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stale chat-list subscription rows must not overwrite a fresher in-memory
 * read watermark or resurrect an inflated [unreadCount]. Exercises
 * [reduceSubscriptionChatListRow] directly.
 */
class ChatListFoldUnreadRegressionTest {
    @Test
    fun delayedPreMarkReadSubscription_keepsNewerReadWatermarkAndUnread() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = idTail,
            )
        val staleSubscription =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 47uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                staleSubscription,
                ChatListUpdateTriggerFfi.UNREAD_CHANGED,
            )

        assertEquals(0uL, folded.unreadCount)
        assertEquals(false, folded.hasUnread)
        assertEquals(200uL, folded.lastReadTimelineAt)
        assertEquals(idTail, folded.lastReadMessageIdHex)
        assertEquals(idTail, folded.lastMessage?.messageIdHex)
    }

    @Test
    fun incompleteReadTuple_keepsWatermarkAndRejectsInflatedUnread() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = idTail,
            )
        val staleSubscription =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 47uL,
                lastReadTimelineAt = null,
                lastReadMessageIdHex = null,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                staleSubscription,
                ChatListUpdateTriggerFfi.UNREAD_CHANGED,
            )

        assertEquals(0uL, folded.unreadCount)
        assertEquals(200uL, folded.lastReadTimelineAt)
        assertEquals(idTail, folded.lastReadMessageIdHex)
    }

    @Test
    fun newLastMessage_adoptsPreviewWhilePreservingNewerReadWatermark() {
        val current =
            row(
                messageId = idMid,
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idMid,
            )
        val incoming =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 47uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            )

        assertEquals(idTail, folded.lastMessage?.messageIdHex)
        assertEquals(100uL, folded.lastReadTimelineAt)
        assertEquals(idMid, folded.lastReadMessageIdHex)
        assertEquals(1uL, folded.unreadCount)
        assertEquals(true, folded.hasUnread)
    }

    @Test
    fun newLastMessage_addsUnreadWhenNeitherRowHasAReadWatermark() {
        val current =
            row(
                messageId = idMid,
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = null,
                lastReadMessageIdHex = null,
            )
        val incoming =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 1uL,
                lastReadTimelineAt = null,
                lastReadMessageIdHex = null,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            )

        assertEquals(1uL, folded.unreadCount)
        assertEquals(true, folded.hasUnread)
        assertEquals(idTail, folded.firstUnreadMessageIdHex)
    }

    @Test
    fun distinctSameSecondLastMessageAddsUnreadRegardlessOfIdentifierOrder() {
        val current =
            row(
                messageId = idB,
                lastMessageAt = 200uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = idB,
            )
        val incoming =
            row(
                messageId = idA,
                lastMessageAt = 200uL,
                unreadCount = 1uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idAnchor,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            )

        assertEquals(idA, folded.lastMessage?.messageIdHex)
        assertEquals(1uL, folded.unreadCount)
        assertEquals(true, folded.hasUnread)
        assertEquals(idA, folded.firstUnreadMessageIdHex)
        assertEquals(200uL, folded.lastReadTimelineAt)
        assertEquals(idB, folded.lastReadMessageIdHex)
    }

    @Test
    fun lastMessageDeleted_adoptsBackwardPreviewAndPreservesReadWatermark() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = idTail,
            )
        val incoming =
            row(
                messageId = idMid,
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idMid,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
            )

        assertEquals(idMid, folded.lastMessage?.messageIdHex)
        assertEquals(100uL, folded.lastMessage?.timelineAt)
        assertEquals(false, folded.lastMessage?.deleted)
        assertEquals(200uL, folded.lastReadTimelineAt)
        assertEquals(idTail, folded.lastReadMessageIdHex)
        assertEquals(0uL, folded.unreadCount)
    }

    @Test
    fun lastMessageDeleted_adoptsNullPreviewWhenTimelineEmpties() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = idTail,
            )
        val incoming =
            row(
                messageId = idMid,
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idMid,
                includeLastMessage = false,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
            )

        assertNull(folded.lastMessage)
        assertEquals(200uL, folded.lastReadTimelineAt)
        assertEquals(idTail, folded.lastReadMessageIdHex)
    }

    @Test
    fun newLastMessage_incrementsExistingUnreadWhenStaleRead() {
        val current =
            row(
                messageId = idMid,
                lastMessageAt = 100uL,
                unreadCount = 3uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idMid,
                firstUnreadMessageIdHex = idA,
            )
        val incoming =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 47uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            )

        assertEquals(4uL, folded.unreadCount)
        assertEquals(true, folded.hasUnread)
        assertEquals(idA, folded.firstUnreadMessageIdHex)
    }

    @Test
    fun newLastMessage_setsFirstUnreadToNewMessageWhenWasRead() {
        val current =
            row(
                messageId = idMid,
                lastMessageAt = 100uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idMid,
            )
        val incoming =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 47uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
                firstUnreadMessageIdHex = idAnchor,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            )

        assertEquals(1uL, folded.unreadCount)
        assertEquals(idTail, folded.firstUnreadMessageIdHex)
    }

    @Test
    fun newLastMessage_doesNotIncrementWhenReadWatermarkCoversPreview() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = idTail,
            )
        val incoming =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 47uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            )

        assertEquals(0uL, folded.unreadCount)
        assertEquals(false, folded.hasUnread)
        assertEquals(200uL, folded.lastReadTimelineAt)
        assertEquals(idTail, folded.lastReadMessageIdHex)
    }

    @Test
    fun newLastMessage_boundedMentionIncrementWhenStaleRead() {
        val current =
            row(
                messageId = idMid,
                lastMessageAt = 100uL,
                unreadCount = 2uL,
                unreadMentionCount = 1uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idMid,
            )
        val incoming =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 47uL,
                unreadMentionCount = 9uL,
                unreadMention = true,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            )

        assertEquals(3uL, folded.unreadCount)
        assertEquals(2uL, folded.unreadMentionCount)
        assertEquals(true, folded.unreadMention)
    }

    @Test
    fun unreadChangedBackfill_increasesUnreadWhenReadTupleIsCurrent() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 1uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )
        val delayedBackfill =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 2uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                delayedBackfill,
                ChatListUpdateTriggerFfi.UNREAD_CHANGED,
            )

        assertEquals(2uL, folded.unreadCount)
        assertEquals(true, folded.hasUnread)
    }

    @Test
    fun freshNewLastMessage_adoptsUnreadWhenReadTupleMatches() {
        val current =
            row(
                messageId = idMid,
                lastMessageAt = 100uL,
                unreadCount = 1uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )
        val incoming =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 2uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = idAnchor,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
            )

        assertEquals(2uL, folded.unreadCount)
        assertEquals(idTail, folded.lastMessage?.messageIdHex)
    }

    @Test
    fun matchingReadWatermarkAfterMarkRead_normalizesStaleUnreadFields() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = idTail,
            )
        val staleDelayedRows =
            listOf(
                row(
                    messageId = idTail,
                    lastMessageAt = 200uL,
                    unreadCount = 3uL,
                    lastReadTimelineAt = 200uL,
                    lastReadMessageIdHex = idTail,
                    unreadMentionCount = 2uL,
                    unreadMention = true,
                ).copy(manuallyMarkedUnread = true),
                row(
                    messageId = idTail,
                    lastMessageAt = 200uL,
                    unreadCount = 0uL,
                    lastReadTimelineAt = 200uL,
                    lastReadMessageIdHex = idTail,
                    unreadMentionCount = 2uL,
                    unreadMention = true,
                ),
            )

        staleDelayedRows.forEach { staleDelayed ->
            val folded =
                reduceSubscriptionChatListRow(
                    current,
                    staleDelayed,
                    ChatListUpdateTriggerFfi.UNREAD_CHANGED,
                )

            assertEquals(0uL, folded.unreadCount)
            assertEquals(false, folded.hasUnread)
            assertNull(folded.firstUnreadMessageIdHex)
            assertEquals(0uL, folded.unreadMentionCount)
            assertEquals(false, folded.unreadMention)
            assertEquals(staleDelayed.manuallyMarkedUnread, folded.manuallyMarkedUnread)
        }
    }

    @Test
    fun equalTimestampLowerReadMessageId_keepsCurrentWatermarkAndUnread() {
        val current =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 0uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idB,
            )
        val incoming =
            row(
                messageId = idTail,
                lastMessageAt = 200uL,
                unreadCount = 3uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = idA,
            )

        val folded =
            reduceSubscriptionChatListRow(
                current,
                incoming,
                ChatListUpdateTriggerFfi.UNREAD_CHANGED,
            )

        assertEquals(0uL, folded.unreadCount)
        assertEquals(idB, folded.lastReadMessageIdHex)
        assertEquals(100uL, folded.lastReadTimelineAt)
    }

    @Test
    fun unreadCountDivergenceReport_flagsInflatedProjectionWithinLoadedWindow() {
        val timeline =
            listOf(
                received("r1"),
                received("r2"),
                received("r3"),
            )
        val report =
            unreadCountDivergenceReport(
                projectionUnread = 3,
                timeline = timeline,
                readAnchorMessageId = "r2",
            )

        requireNotNull(report)
        assertEquals(3, report.projectionUnread)
        assertEquals(1, report.timelineUnread)
        assertEquals(3, report.loadedReceivedCount)
    }

    @Test
    fun unreadCountDivergenceReport_isNullWhenProjectionExceedsLoadedWindow() {
        val timeline = listOf(received("r1"), received("r2"))
        assertNull(
            unreadCountDivergenceReport(
                projectionUnread = 47,
                timeline = timeline,
                readAnchorMessageId = "r1",
            ),
        )
    }

    @Test
    fun unreadCountDivergenceReport_isNullWhenProjectionIsNotAboveTimeline() {
        val timeline = listOf(received("r1"), received("r2"))
        assertNull(
            unreadCountDivergenceReport(
                projectionUnread = 1,
                timeline = timeline,
                readAnchorMessageId = "r1",
            ),
        )
    }

    private val idAnchor = "a".repeat(64)
    private val idMid = "b".repeat(64)
    private val idTail = "c".repeat(64)
    private val idA = "d".repeat(64)
    private val idB = "e".repeat(64)

    private fun row(
        messageId: String,
        lastMessageAt: ULong,
        unreadCount: ULong,
        lastReadTimelineAt: ULong?,
        lastReadMessageIdHex: String?,
        deleted: Boolean = false,
        includeLastMessage: Boolean = true,
        firstUnreadMessageIdHex: String = messageId,
        unreadMentionCount: ULong = 0uL,
        unreadMention: Boolean = unreadMentionCount > 0uL,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = unreadMentionCount,
        unreadMention = unreadMention,
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
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    timelineAt = lastMessageAt,
                    deleted = deleted,
                    attachmentKind = null,
                    attachmentCount = 0u,
                    deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                )
            } else {
                null
            },
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = firstUnreadMessageIdHex,
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

    private fun received(id: String): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = "group",
                    sender = "peer",
                    plaintext = "hi",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            status = MessageStatus.Received,
        )
}
