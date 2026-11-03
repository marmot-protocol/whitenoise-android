package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.TimelineProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A failed local row keeps its presentation until both its window and optimistic owner release it. */
class TimelineMessageOverlayRetentionTest {
    @Test
    fun failedRowKeepsBodyOrderRetentionAndOverlayUntilItsOwnerLeavesTheWindow() {
        val loaded = TimelineProjector.toAppMessageRecord(timelineRecord("loaded", 1uL, plaintext = "loaded body"))
        val record =
            TimelineProjector
                .toAppMessageRecord(timelineRecord("pending", 2uL, plaintext = "copyable own body"))
                .copy(direction = "sent", sender = ConversationTimelineTestIds.ACCOUNT_ID)
        val stale = TimelineProjector.toAppMessageRecord(timelineRecord("stale", 0uL, plaintext = "evicted body"))
        val optimistic =
            linkedMapOf(
                "msg:pending" to
                    TimelineMessage(
                        id = "msg:pending",
                        record = record,
                        status = MessageStatus.Pending,
                        timelineOrder = 7uL,
                        retentionAtSendSeconds = 30uL,
                    ),
            )
        val records = linkedMapOf(loaded.messageIdHex to loaded, stale.messageIdHex to stale)
        val windowIds = setOf(loaded.messageIdHex)
        val overlays = setOf(record.messageIdHex, loaded.messageIdHex, stale.messageIdHex)

        retainFailedOptimisticTextSend(optimistic, records, "msg:pending", record, 7uL)
        pruneMessageByIdToWindow(records, windowIds, optimistic.values)

        val failed = checkNotNull(optimistic["msg:pending"])
        assertEquals(MessageStatus.Failed, failed.status)
        assertEquals("copyable own body", failed.record.plaintext)
        assertEquals(7uL, failed.timelineOrder)
        assertEquals(30uL, failed.retentionAtSendSeconds)
        assertEquals(setOf("loaded", "pending"), records.keys)
        assertEquals(
            setOf("loaded", "pending"),
            retainedMessageOverlayTargets(windowIds, optimistic.values.map { it.record.messageIdHex }, overlays),
        )
        assertEquals(
            30uL,
            retentionHintForProjection(null, 0uL, failed.retentionAtSendSeconds, null),
        )

        optimistic.clear()
        pruneMessageByIdToWindow(records, windowIds, optimistic.values)

        assertEquals(setOf("loaded"), records.keys)
        assertEquals(setOf("loaded"), retainedMessageOverlayTargets(windowIds, emptySet(), overlays))
        assertNull(retentionHintForProjection(null, 0uL, null, failed.retentionAtSendSeconds))
    }
}
