package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimisticMessageReconciliationTest {
    @Test
    fun matchingPendingMessageIsReconciledWhenProjectionArrives() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertEquals(
            "temp",
            pendingOptimisticMessageIdForProjection(listOf(pending), message("confirmed")),
        )
    }

    @Test
    fun sentAndDifferentPendingMessagesAreNotReconciled() {
        val sent = timelineMessage("sent", MessageStatus.Sent)
        val different = timelineMessage("different", MessageStatus.Pending, plaintext = "another")

        assertNull(
            pendingOptimisticMessageIdForProjection(
                listOf(sent, different),
                message("confirmed"),
            ),
        )
    }

    @Test
    fun historicalMatchingMessageIsNotReconciled() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertNull(
            pendingOptimisticMessageIdForProjection(
                listOf(pending),
                message("historical", recordedAt = 10uL),
            ),
        )
    }

    @Test
    fun receivedMatchingMessageIsNotReconciled() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertNull(
            pendingOptimisticMessageIdForProjection(
                listOf(pending),
                message("received", direction = "received"),
            ),
        )
    }

    private fun timelineMessage(
        id: String,
        status: MessageStatus,
        plaintext: String = "hello",
    ): TimelineMessage {
        return TimelineMessage(
            id = "msg:$id",
            record = message(id, plaintext),
            status = status,
        )
    }

    private fun message(
        id: String,
        plaintext: String = "hello",
        recordedAt: ULong = 1uL,
        direction: String = "sent",
    ): AppMessageRecordFfi {
        return AppMessageRecordFfi(
            messageIdHex = id,
            direction = direction,
            groupIdHex = "group",
            sender = "alice",
            plaintext = plaintext,
            kind = 9uL,
            tags = emptyList(),
            recordedAt = recordedAt,
            receivedAt = recordedAt,
        )
    }
}
