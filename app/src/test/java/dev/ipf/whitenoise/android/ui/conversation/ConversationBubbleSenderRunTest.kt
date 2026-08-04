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
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
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
