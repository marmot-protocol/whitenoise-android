package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBubbleSenderRunTest {
    @Test
    fun deletedAgentOperationFallbackKeepsSenderRunContinuous() {
        val message = timelineMessage(id = "message", kind = 9uL, recordedAt = 100uL)
        val operation = timelineMessage(id = "operation", kind = 1202uL, recordedAt = 101uL)

        assertTrue(
            conversationBubbleRowsShareSenderRun(
                first = message,
                second = operation,
                streamingDebugEnabled = false,
                deletedMessageIds = setOf(operation.record.messageIdHex),
            ),
        )
    }

    @Test
    fun outgoingInterruptionBreaksTranscriptSenderRun() {
        val incoming = timelineMessage(id = "incoming", kind = 9uL, recordedAt = 100uL, direction = "received")
        val outgoing = timelineMessage(id = "outgoing", kind = 9uL, recordedAt = 101uL, direction = "sent")

        assertFalse(
            conversationBubbleRowsShareSenderRun(
                first = incoming,
                second = outgoing,
                streamingDebugEnabled = false,
                deletedMessageIds = emptySet(),
            ),
        )
        assertFalse(
            conversationBubbleRowsShareSenderRun(
                first = outgoing,
                second = incoming,
                streamingDebugEnabled = false,
                deletedMessageIds = emptySet(),
            ),
        )
    }

    @Test
    fun sameDirectionReceivedMessagesShareSenderRun() {
        val first = timelineMessage(id = "first", kind = 9uL, recordedAt = 100uL, direction = "received")
        val second = timelineMessage(id = "second", kind = 9uL, recordedAt = 101uL, direction = "received")

        assertTrue(
            conversationBubbleRowsShareSenderRun(
                first = first,
                second = second,
                streamingDebugEnabled = false,
                deletedMessageIds = emptySet(),
            ),
        )
    }

    @Test
    fun senderRunEndsAfterThreeMinuteWindow() {
        val first = timelineMessage(id = "first", kind = 9uL, recordedAt = 100uL)
        val boundary = timelineMessage(id = "boundary", kind = 9uL, recordedAt = 280uL)
        val outside = timelineMessage(id = "outside", kind = 9uL, recordedAt = 281uL)

        assertTrue(
            conversationBubbleRowsShareSenderRun(
                first = first,
                second = boundary,
                streamingDebugEnabled = false,
                deletedMessageIds = emptySet(),
            ),
        )
        assertFalse(
            conversationBubbleRowsShareSenderRun(
                first = first,
                second = outside,
                streamingDebugEnabled = false,
                deletedMessageIds = emptySet(),
            ),
        )
    }

    @Test
    fun calendarDayBoundaryBreaksSenderRun() {
        val first = timelineMessage(id = "first", kind = 9uL, recordedAt = 0uL)
        val second = timelineMessage(id = "second", kind = 9uL, recordedAt = 172_800uL)

        assertFalse(
            conversationBubbleRowsShareSenderRun(
                first = first,
                second = second,
                streamingDebugEnabled = false,
                deletedMessageIds = emptySet(),
            ),
        )
    }

    @Test
    fun groupSystemRowBreaksSenderRun() {
        val message = timelineMessage(id = "message", kind = 9uL, recordedAt = 100uL)
        val system =
            TimelineMessage(
                id = "msg:system",
                record =
                    AppMessageRecordFfi(
                        messageIdHex = "system",
                        direction = "received",
                        groupIdHex = "group",
                        sender = "alice",
                        plaintext = "joined",
                        contentTokens =
                            MarkdownDocumentFfi(
                                truncated = false,
                                blocks = emptyList(),
                                blankLinesBefore = ByteArray(0),
                            ),
                        kind = 1210uL,
                        tags = emptyList(),
                        sourceEpoch = null,
                        retentionSeconds = null,
                        retentionExpiresAt = null,
                        recordedAt = 101uL,
                        receivedAt = 101uL,
                    ),
                status = MessageStatus.Received,
                timelineOrder = 101uL,
            )

        assertFalse(
            conversationBubbleRowsShareSenderRun(
                first = message,
                second = system,
                streamingDebugEnabled = false,
                deletedMessageIds = emptySet(),
            ),
        )
    }

    @Test
    fun dedicatedAgentOperationBreaksSenderRun() {
        val message = timelineMessage(id = "message", kind = 9uL, recordedAt = 100uL)
        val operation = timelineMessage(id = "operation", kind = 1202uL, recordedAt = 101uL)

        assertFalse(
            conversationBubbleRowsShareSenderRun(
                first = message,
                second = operation,
                streamingDebugEnabled = false,
                deletedMessageIds = emptySet(),
            ),
        )
    }

    private fun timelineMessage(
        id: String,
        kind: ULong,
        recordedAt: ULong,
        direction: String = "received",
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = direction,
                    groupIdHex = "group",
                    sender = "alice",
                    plaintext = if (kind == 1202uL) "{\"text\":\"operation\"}" else "message",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = kind,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = recordedAt,
                    receivedAt = recordedAt,
                ),
            status = MessageStatus.Received,
            timelineOrder = recordedAt,
        )
}
