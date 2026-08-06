package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The record list read-aloud paging anchors against: only rows the engine
 * projected, because local-only ids vanish under the anchor's feet.
 */
class CanonicalTimelineRecordsTest {
    @Test
    fun localOnlyRowsAreExcludedIncludingAtTheWindowTail() {
        val items =
            listOf(
                projectedTimelineMessage(timelineAppMessage("m1", recordedAt = 1uL)),
                projectedTimelineMessage(timelineAppMessage("m2", recordedAt = 2uL)),
                // An in-flight send and a stream-debug row, both at the tail.
                localTimelineMessage(timelineAppMessage("temp1", recordedAt = 3uL)),
                localTimelineMessage(timelineAppMessage("dbg1", recordedAt = 4uL)),
            )

        val records = canonicalTimelineRecords(items)

        assertEquals(listOf("m1", "m2"), records.map { it.messageIdHex })
        assertEquals("m2", records.last().messageIdHex)
    }

    @Test
    fun anAllProjectedWindowKeepsEveryRowInOrder() {
        val items = (1..3).map { projectedTimelineMessage(timelineAppMessage("m$it", recordedAt = it.toULong())) }

        assertEquals(
            listOf("m1", "m2", "m3"),
            canonicalTimelineRecords(items).map { it.messageIdHex },
        )
    }
}
