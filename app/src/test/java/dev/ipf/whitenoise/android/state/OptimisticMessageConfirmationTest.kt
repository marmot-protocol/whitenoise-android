package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class OptimisticMessageConfirmationTest {
    @Test
    fun matchingPendingMessageIsReconciledWhenProjectionArrives() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertEquals(
            "temp",
            optimisticMessageIdForProjection(listOf(pending), message("confirmed")),
        )
    }

    @Test
    fun sentOptimisticMessageIsReconciledWhenProjectionArrivesAfterResponse() {
        val sent = timelineMessage("sent", MessageStatus.Sent)

        assertEquals(
            "sent",
            optimisticMessageIdForProjection(listOf(sent), message("confirmed")),
        )
    }

    @Test
    fun textSendSummaryWaitsOnlyWhileTempBubbleStillExists() {
        assertEquals(true, textSendAwaitingEchoConfirmation(emptyList(), optimisticStillPresent = true))
        assertEquals(false, textSendAwaitingEchoConfirmation(emptyList(), optimisticStillPresent = false))
        assertEquals(false, textSendAwaitingEchoConfirmation(listOf("confirmed-id"), optimisticStillPresent = true))
    }

    @Test
    fun sentOptimisticReplacementIsSkippedWhenProjectionArrivesBeforeResponse() {
        assertEquals(
            false,
            shouldInsertSentOptimisticMessage("confirmed", setOf("confirmed")),
        )
        assertEquals(
            true,
            shouldInsertSentOptimisticMessage("confirmed", emptySet()),
        )
    }

    private fun timelineMessage(
        id: String,
        status: MessageStatus,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record = message(id),
            status = status,
        )

    private fun message(id: String): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "hello",
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
        )
}
