package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
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
    fun aTimelineInTrueOrderReportsNoInversions() {
        val rows = listOf(row("a", renderedAt = 1uL), row("b", renderedAt = 2uL), row("c", renderedAt = 3uL))

        val inversions = detectTimelineInversions(rows) { trueAt.getValue(it.record.messageIdHex) }

        assertTrue(inversions.isEmpty())
    }

    @Test
    fun aStaleOverridePinningANewerRowAboveAnOlderOneIsDetected() {
        // "b" carries an override that renders it at 1 (above "a" at 2), but its
        // true send time is 3 — newer than "a". That is the reported #1578 symptom.
        val rows = listOf(row("b", renderedAt = 1uL), row("a", renderedAt = 2uL))

        val inversions = detectTimelineInversions(rows) { trueAt.getValue(it.record.messageIdHex) }

        assertEquals(1, inversions.size)
        val inversion = inversions.single()
        assertEquals("b", inversion.upperId)
        assertEquals("a", inversion.lowerId)
        assertEquals(3uL, inversion.upperTrueAt)
        assertEquals(2uL, inversion.lowerTrueAt)
    }

    @Test
    fun releasingTheStaleOverrideRestoresTrueChronologicalOrder() {
        // With the stale override, "b" (rendered 1) sorts above older "a" (rendered 2).
        val pinned =
            listOf(row("b", renderedAt = 1uL), row("a", renderedAt = 2uL))
                .sortedWith(::compareTimelineMessages)
        assertEquals(listOf("b", "a"), pinned.map { it.record.messageIdHex })
        assertEquals(1, detectTimelineInversions(pinned) { trueAt.getValue(it.record.messageIdHex) }.size)

        // Orphan release re-seats "b" to its true send time (3); the order corrects
        // and no inversion remains.
        val released =
            listOf(row("b", renderedAt = 3uL), row("a", renderedAt = 2uL))
                .sortedWith(::compareTimelineMessages)
        assertEquals(listOf("a", "b"), released.map { it.record.messageIdHex })
        assertTrue(detectTimelineInversions(released) { trueAt.getValue(it.record.messageIdHex) }.isEmpty())
    }

    private val trueAt = mapOf("a" to 2uL, "b" to 3uL, "c" to 3uL)

    private fun row(
        id: String,
        renderedAt: ULong,
        order: ULong = 0uL,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "sent",
                    groupIdHex = "group",
                    sender = "alice",
                    plaintext = "hi",
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                    kind = 9uL,
                    tags = emptyList(),
                    recordedAt = renderedAt,
                    receivedAt = renderedAt,
                ),
            status = MessageStatus.Sent,
            timelineOrder = order,
        )
}
