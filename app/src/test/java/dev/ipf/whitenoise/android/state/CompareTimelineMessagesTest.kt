package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareTimelineMessagesTest {
    private fun msg(
        id: String,
        recordedAt: ULong,
        order: ULong,
    ) = TimelineMessage(
        id = id,
        record =
            AppMessageRecordFfi(
                messageIdHex = id,
                direction = "received",
                groupIdHex = "g",
                sender = "s",
                plaintext = "",
                contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                kind = 9uL,
                tags = emptyList(),
                recordedAt = recordedAt,
                receivedAt = recordedAt,
            ),
        status = MessageStatus.Received,
        timelineOrder = order,
    )

    @Test
    fun sortResultIsIndependentOfInputOrder() {
        // compareTimelineMessages breaks every tie on the unique id, so it is a
        // total order: a list sorts to the same sequence regardless of starting
        // order. ConversationController relies on this to keep `timelineOrder`
        // as an unordered membership set (publishTimelineFromIndexes re-sorts),
        // which is what lets insertTimelineItemId append in O(1). See #74.
        val a = msg("a", recordedAt = 100uL, order = 1uL)
        val b = msg("b", recordedAt = 100uL, order = 1uL) // tie with a on time+order → id wins
        val c = msg("c", recordedAt = 50uL, order = 5uL)
        val d = msg("d", recordedAt = 200uL, order = 0uL)

        val expected = listOf("c", "a", "b", "d")
        listOf(
            listOf(a, b, c, d),
            listOf(d, c, b, a),
            listOf(b, d, a, c),
            listOf(c, a, d, b),
        ).forEach { permutation ->
            assertEquals(expected, permutation.sortedWith(::compareTimelineMessages).map { it.id })
        }
    }

    @Test
    fun adjacentTimelineInversionsCapturesAnOverrideThatReversesEngineOrder() {
        val older = projectedMsg("older", displayedAt = 200uL, timelineAt = 100uL, receivedAt = 100uL, order = 9uL)
        val newer = projectedMsg("newer", displayedAt = 150uL, timelineAt = 150uL, receivedAt = 150uL, order = 0uL)

        val inversion =
            adjacentTimelineInversions(
                listOf(older, newer).sortedWith(::compareTimelineMessages),
            ).single()

        assertEquals("newer", inversion.above.record.messageIdHex)
        assertEquals("older", inversion.below.record.messageIdHex)
        assertEquals(150uL, inversion.above.record.recordedAt)
        assertEquals(100uL, inversion.below.projected?.timelineAt)
        assertTrue(inversion.sourceTimelineInverted)
        assertTrue(inversion.arrivalInverted)
    }

    @Test
    fun adjacentTimelineInversionsCapturesSenderTimeSkewAgainstArrivalOrder() {
        val delayedOlder =
            projectedMsg("older", displayedAt = 100uL, timelineAt = 100uL, receivedAt = 200uL, order = 0uL)
        val arrivedFirst =
            projectedMsg("newer", displayedAt = 150uL, timelineAt = 150uL, receivedAt = 100uL, order = 0uL)

        val inversion =
            adjacentTimelineInversions(
                listOf(delayedOlder, arrivedFirst).sortedWith(::compareTimelineMessages),
            ).single()

        assertEquals("older", inversion.above.record.messageIdHex)
        assertEquals("newer", inversion.below.record.messageIdHex)
        assertFalse(inversion.sourceTimelineInverted)
        assertTrue(inversion.arrivalInverted)
        assertEquals("received", inversion.above.record.direction)
    }

    @Test
    fun adjacentTimelineInversionsIgnoresChronologicalAndOptimisticPairs() {
        val older = projectedMsg("older", displayedAt = 100uL, timelineAt = 100uL, receivedAt = 100uL, order = 0uL)
        val newer = projectedMsg("newer", displayedAt = 150uL, timelineAt = 150uL, receivedAt = 150uL, order = 0uL)
        val optimistic = msg("optimistic", recordedAt = 200uL, order = 1uL)

        assertTrue(
            adjacentTimelineInversions(listOf(older, newer, optimistic)).isEmpty(),
        )
    }

    private fun projectedMsg(
        id: String,
        displayedAt: ULong,
        timelineAt: ULong,
        receivedAt: ULong,
        order: ULong,
    ): TimelineMessage {
        val record =
            TimelineMessageRecordFfi(
                messageIdHex = id,
                sourceMessageIdHex = id,
                direction = "received",
                groupIdHex = "g",
                sender = "s",
                plaintext = "",
                contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                kind = 9uL,
                tags = emptyList(),
                timelineAt = timelineAt,
                receivedAt = receivedAt,
                replyToMessageIdHex = null,
                replyPreview = null,
                mediaJson = null,
                media = emptyList(),
                agentTextStreamJson = null,
                groupSystem = null,
                reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
                deleted = false,
                deletedByMessageIdHex = null,
                invalidationStatus = null,
            )
        return TimelineMessage(
            id = "msg:$id",
            record = msg(id, displayedAt, order).record,
            status = MessageStatus.Received,
            projected = record,
            timelineOrder = order,
        )
    }
}
