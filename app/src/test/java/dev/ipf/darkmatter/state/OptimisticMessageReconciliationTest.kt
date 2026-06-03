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

    @Test
    fun queuedPendingMessagesReconcileOnlyTheMatchingProjection() {
        val first = timelineMessage("first-temp", MessageStatus.Pending, plaintext = "first")
        val second = timelineMessage("second-temp", MessageStatus.Pending, plaintext = "second")

        assertEquals(
            "first-temp",
            optimisticMessageIdForProjection(
                listOf(first, second),
                message("first-confirmed", plaintext = "first"),
            ),
        )
    }

    @Test
    fun delayedQueuedProjectionCanReconcileAfterWorkerWait() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertEquals(
            "temp",
            optimisticMessageIdForProjection(
                listOf(pending),
                message("confirmed", recordedAt = 10uL),
                allowDelayedProjection = true,
            ),
        )
    }

    @Test
    fun delayedHistoricalSnapshotDoesNotConsumeNewerPendingMessage() {
        val pending = timelineMessage("temp", MessageStatus.Pending, recordedAt = 10uL)

        assertNull(
            optimisticMessageIdForProjection(
                listOf(pending),
                message("historical", recordedAt = 1uL),
                allowDelayedProjection = true,
            ),
        )
    }

    @Test
    fun failedAndDifferentPendingMessagesAreNotReconciled() {
        val failed = timelineMessage("failed", MessageStatus.Failed)
        val different = timelineMessage("different", MessageStatus.Pending, plaintext = "another")

        assertNull(
            optimisticMessageIdForProjection(
                listOf(failed, different),
                message("confirmed"),
            ),
        )
    }

    @Test
    fun historicalMatchingMessageIsNotReconciled() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertNull(
            optimisticMessageIdForProjection(
                listOf(pending),
                message("historical", recordedAt = 10uL),
            ),
        )
    }

    @Test
    fun receivedMatchingMessageIsNotReconciled() {
        val pending = timelineMessage("temp", MessageStatus.Pending)

        assertNull(
            optimisticMessageIdForProjection(
                listOf(pending),
                message("received", direction = "received"),
            ),
        )
    }

    @Test
    fun queuedMessagesKeepTheirOrderWhenIdsChangeDuringConfirmation() {
        val first = timelineMessage("first-temp", MessageStatus.Pending, plaintext = "A", timelineOrder = 1uL)
        val second = timelineMessage("second-temp", MessageStatus.Pending, plaintext = "B", timelineOrder = 2uL)
        val third = timelineMessage("third-temp", MessageStatus.Pending, plaintext = "C", timelineOrder = 3uL)
        val confirmedFirst = timelineMessage("zz-confirmed", MessageStatus.Sent, plaintext = "A", timelineOrder = 1uL)

        assertEquals(
            listOf("A", "B", "C"),
            listOf(second, third, confirmedFirst)
                .sortedWith(::compareTimelineMessages)
                .map { it.record.plaintext },
        )
    }

    private fun timelineMessage(
        id: String,
        status: MessageStatus,
        plaintext: String = "hello",
        timelineOrder: ULong = 0uL,
        recordedAt: ULong = 1uL,
    ): TimelineMessage {
        return TimelineMessage(
            id = "msg:$id",
            record = message(id, plaintext, recordedAt),
            status = status,
            timelineOrder = timelineOrder,
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
