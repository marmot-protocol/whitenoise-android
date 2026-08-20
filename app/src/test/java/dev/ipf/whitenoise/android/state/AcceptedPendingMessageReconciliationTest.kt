package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptedPendingMessageReconciliationTest {
    @Test
    fun acceptedPendingTextSendKeepsTheBubblePendingUntilProjection() {
        val tempId = "temp-accepted-pending"
        val key = "msg:$tempId"
        val record = message(tempId)
        val optimisticMessages =
            linkedMapOf(
                key to TimelineMessage(id = key, record = record, status = MessageStatus.Pending),
            )
        val messageById = linkedMapOf(tempId to record)

        val reconciliation =
            reconcileSuccessfulTextSend(
                summaryMessageIds = emptyList(),
                acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                optimisticKey = key,
                tempId = tempId,
                optimisticRecord = record,
                optimisticMessages = optimisticMessages,
                messageById = messageById,
                projectedMessageIds = emptySet(),
                timelineOrder = 13uL,
            )

        assertFalse(reconciliation.awaitingEcho)
        assertTrue(reconciliation.acceptedPending)
        assertTrue(reconciliation.awaitingProjection)
        assertFalse(reconciliation.insertedSent)
        assertEquals(MessageStatus.Pending, optimisticMessages[key]?.status)
        assertEquals(record, messageById[tempId])
    }

    private fun message(id: String): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "queued for publication",
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
