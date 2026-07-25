package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOrderingInversionTest {
    @Test
    fun orphanReleaseTargetsPreservesWhoseBubbleIsGoneAndProjectionLanded() {
        val orphaned =
            orphanedOptimisticSendPreserveIds(
                optimisticSendPreserveIds = setOf("confirmed"),
                optimisticKeys = emptySet(),
                projectedMessageIds = setOf("confirmed"),
            )

        assertEquals(setOf("confirmed"), orphaned)
    }

    @Test
    fun orphanReleaseKeepsAPreserveWhileItsOptimisticBubbleStillCoexists() {
        val orphaned =
            orphanedOptimisticSendPreserveIds(
                optimisticSendPreserveIds = setOf("confirmed"),
                // The "Sent" bubble for this id is still on screen during handoff.
                optimisticKeys = setOf("msg:confirmed"),
                projectedMessageIds = setOf("confirmed"),
            )

        assertTrue(orphaned.isEmpty())
    }

    @Test
    fun orphanReleaseWaitsUntilTheProjectionActuallyExists() {
        val orphaned =
            orphanedOptimisticSendPreserveIds(
                optimisticSendPreserveIds = setOf("confirmed"),
                optimisticKeys = emptySet(),
                // Projection not yet applied — nothing to settle onto.
                projectedMessageIds = emptySet(),
            )

        assertTrue(orphaned.isEmpty())
    }

    @Test
    fun adjacentInversionsCaptureAnOverrideThatReversesEngineOrder() {
        // "older" carries a display override (200) that renders it below the newer
        // row, but its engine timeline time (100) is older — the #1578 symptom.
        val older = projectedMsg("older", displayedAt = 200uL, timelineAt = 100uL, receivedAt = 100uL, order = 9uL)
        val newer = projectedMsg("newer", displayedAt = 150uL, timelineAt = 150uL, receivedAt = 150uL, order = 0uL)

        val inversion =
            adjacentTimelineInversions(
                listOf(older, newer).sortedWith(::compareTimelineMessages),
            ).single()

        assertEquals("newer", inversion.above.record.messageIdHex)
        assertEquals("older", inversion.below.record.messageIdHex)
        assertTrue(inversion.sourceTimelineInverted)
        assertTrue(inversion.arrivalInverted)
    }

    @Test
    fun adjacentInversionsCaptureSenderTimeSkewAgainstArrivalOrder() {
        // A delayed relay delivery: the older-by-send-time row arrived last, so it
        // is arrival-inverted but NOT source-inverted (faithful engine skew, #3).
        val delayedOlder =
            projectedMsg("older", displayedAt = 100uL, timelineAt = 100uL, receivedAt = 200uL, order = 0uL)
        val arrivedFirst =
            projectedMsg("newer", displayedAt = 150uL, timelineAt = 150uL, receivedAt = 100uL, order = 0uL)

        val inversion =
            adjacentTimelineInversions(
                listOf(delayedOlder, arrivedFirst).sortedWith(::compareTimelineMessages),
            ).single()

        assertFalse(inversion.sourceTimelineInverted)
        assertTrue(inversion.arrivalInverted)
    }

    @Test
    fun adjacentInversionsIgnoreChronologicalAndOptimisticPairs() {
        val older = projectedMsg("older", displayedAt = 100uL, timelineAt = 100uL, receivedAt = 100uL, order = 0uL)
        val newer = projectedMsg("newer", displayedAt = 150uL, timelineAt = 150uL, receivedAt = 150uL, order = 0uL)
        // An optimistic row has no projection yet, so it is never flagged.
        val optimistic = optimisticMsg("optimistic", recordedAt = 200uL, order = 1uL)

        assertTrue(adjacentTimelineInversions(listOf(older, newer, optimistic)).isEmpty())
    }

    private fun projectedMsg(
        id: String,
        displayedAt: ULong,
        timelineAt: ULong,
        receivedAt: ULong,
        order: ULong,
    ): TimelineMessage {
        val projected =
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
            record = record(id, recordedAt = displayedAt),
            status = MessageStatus.Sent,
            projected = projected,
            timelineOrder = order,
        )
    }

    private fun optimisticMsg(
        id: String,
        recordedAt: ULong,
        order: ULong,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record = record(id, recordedAt = recordedAt),
            status = MessageStatus.Pending,
            timelineOrder = order,
        )

    private fun record(
        id: String,
        recordedAt: ULong,
    ): AppMessageRecordFfi =
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
        )
}
