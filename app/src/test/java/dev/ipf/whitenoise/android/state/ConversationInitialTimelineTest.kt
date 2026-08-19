package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListAttachmentKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInitialTimelineTest {
    @Test
    fun completePlainTextPreviewSeedsOneDisplayRow() {
        val timeline =
            initialConversationTimeline(
                preview = preview(),
                groupIdHex = GROUP_ID,
                pendingConfirmation = false,
                optimisticMessages = emptyList(),
            )

        assertEquals(1, timeline.size)
        assertEquals(MESSAGE_ID, timeline.single().record.messageIdHex)
        assertEquals("cached hello", timeline.single().record.plaintext)
        assertEquals("received", timeline.single().record.direction)
        assertEquals(MessageStatus.Received, timeline.single().status)
    }

    @Test
    fun incompleteOrDerivedPreviewNeverSeedsAProtocolRow() {
        val rejected =
            listOf(
                preview(messageIdHex = "not-hex"),
                preview(sender = "not-hex"),
                preview(plaintext = ""),
                preview(kind = 7uL),
                preview(deleted = true),
                preview(attachmentKind = ChatListAttachmentKindFfi.PHOTO, attachmentCount = 1u),
                preview(contentTokens = markdown(truncated = true)),
                preview(deliveryState = ChatListMessageDeliveryStateFfi.FAILED),
            )

        rejected.forEach { candidate ->
            assertTrue(
                initialConversationTimeline(candidate, GROUP_ID, false, emptyList()).isEmpty(),
            )
        }
    }

    @Test
    fun pendingPlainTextPreviewSeedsTruthfulOutgoingRow() {
        val timeline =
            initialConversationTimeline(
                preview = preview(deliveryState = ChatListMessageDeliveryStateFfi.PENDING),
                groupIdHex = GROUP_ID,
                pendingConfirmation = false,
                optimisticMessages = emptyList(),
            )

        assertEquals("sent", timeline.single().record.direction)
        assertEquals(MessageStatus.Pending, timeline.single().status)
    }

    @Test
    fun pendingInviteDoesNotExposeTheChatListPreview() {
        val optimistic =
            TimelineMessage(
                id = "msg:$MESSAGE_ID",
                record = record(plaintext = "must stay hidden"),
                status = MessageStatus.Pending,
            )
        assertTrue(
            initialConversationTimeline(preview(), GROUP_ID, true, listOf(optimistic)).isEmpty(),
        )
    }

    @Test
    fun optimisticFullRowWinsPreviewDeduplication() {
        val optimistic =
            TimelineMessage(
                id = "msg:$MESSAGE_ID",
                record = record(plaintext = "local optimistic"),
                status = MessageStatus.Pending,
                timelineOrder = 12uL,
            )

        val timeline =
            initialConversationTimeline(
                preview = preview(deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED),
                groupIdHex = GROUP_ID,
                pendingConfirmation = false,
                optimisticMessages = listOf(optimistic),
            )

        assertEquals(listOf(optimistic), timeline)
    }

    @Test
    fun initialFailureDiscardsSeedButRefreshFailureKeepsAuthoritativeContent() {
        assertTrue(shouldDiscardInitialTimelineSeedForFailure(published = false))
        assertFalse(shouldDiscardInitialTimelineSeedForFailure(published = true))
    }

    private fun preview(
        messageIdHex: String = MESSAGE_ID,
        sender: String = SENDER_ID,
        plaintext: String = "cached hello",
        kind: ULong = 9uL,
        deleted: Boolean = false,
        attachmentKind: ChatListAttachmentKindFfi? = null,
        attachmentCount: UInt = 0u,
        contentTokens: MarkdownDocumentFfi = markdown(),
        deliveryState: ChatListMessageDeliveryStateFfi = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
    ) = ChatListMessagePreviewFfi(
        messageIdHex = messageIdHex,
        sender = sender,
        senderDisplayName = "Sender",
        plaintext = plaintext,
        contentTokens = contentTokens,
        kind = kind,
        timelineAt = 10uL,
        deleted = deleted,
        attachmentKind = attachmentKind,
        attachmentCount = attachmentCount,
        deliveryState = deliveryState,
    )

    private fun record(plaintext: String) =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_ID,
            direction = "sent",
            groupIdHex = GROUP_ID,
            sender = SENDER_ID,
            plaintext = plaintext,
            contentTokens = markdown(),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 11uL,
            receivedAt = 11uL,
        )

    private fun markdown(truncated: Boolean = false) =
        MarkdownDocumentFfi(
            truncated = truncated,
            blocks = emptyList(),
            blankLinesBefore = ByteArray(0),
        )

    private companion object {
        val GROUP_ID = "11".repeat(32)
        val MESSAGE_ID = "22".repeat(32)
        val SENDER_ID = "33".repeat(32)
    }
}
