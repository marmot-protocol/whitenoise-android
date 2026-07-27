package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTimelineWindowTrimTest {
    @Test
    fun liveCapTrimsOldestUnprotectedRowsOnly() {
        // Regression for #1163: after loadOlder(), deliberately-loaded history must
        // stay intact while live Upserts beyond the cap are dropped.
        val protected = (1..250).map { "hist$it" }.toSet()
        val items =
            buildList {
                protected.forEach { add(projectedMessage(it, recordedAt = 1uL)) }
                repeat(250) { i -> add(projectedMessage("live$i", recordedAt = 1000uL + i.toULong())) }
            }

        val toDrop =
            timelineMessageIdsExceedingLiveCap(
                items = items,
                protectedIds = protected,
                maxLiveItems = 200,
            )

        assertEquals(50, toDrop.size)
        assertTrue(toDrop.all { it.startsWith("live") })
        assertEquals((0 until 50).map { "live$it" }, toDrop)
        assertTrue(protected.none { it in toDrop })
    }

    @Test
    fun liveCapWithNoProtectedIdsMatchesWholeWindowTrim() {
        val items = (1..250).map { projectedMessage("m$it", recordedAt = it.toULong()) }

        val toDrop =
            timelineMessageIdsExceedingLiveCap(
                items = items,
                protectedIds = emptySet(),
                maxLiveItems = 200,
            )

        assertEquals(50, toDrop.size)
        assertEquals((1..50).map { "m$it" }, toDrop)
    }

    @Test
    fun liveCapNoOpWhenWithinLimit() {
        val protected = setOf("hist1", "hist2")
        val items =
            listOf(
                projectedMessage("hist1", recordedAt = 1uL),
                projectedMessage("hist2", recordedAt = 2uL),
                projectedMessage("live1", recordedAt = 3uL),
            )

        assertEquals(
            emptyList<String>(),
            timelineMessageIdsExceedingLiveCap(
                items = items,
                protectedIds = protected,
                maxLiveItems = 200,
            ),
        )
    }

    @Test
    fun liveCapIgnoresOptimisticRows() {
        val items =
            buildList {
                repeat(250) { i -> add(projectedMessage("m$i", recordedAt = i.toULong())) }
                repeat(5) { i -> add(optimisticMessage("temp$i", recordedAt = 1000uL + i.toULong())) }
            }

        val toDrop =
            timelineMessageIdsExceedingLiveCap(
                items = items,
                protectedIds = emptySet(),
                maxLiveItems = 200,
            )

        assertEquals(50, toDrop.size)
        assertTrue(toDrop.none { it.startsWith("temp") })
    }

    private fun projectedMessage(
        id: String,
        recordedAt: ULong,
    ): TimelineMessage = optimisticMessage(id, recordedAt).copy(projected = timelineRecord(id, recordedAt))

    private fun optimisticMessage(
        id: String,
        recordedAt: ULong,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record = appMessage(id, recordedAt),
            status = MessageStatus.Received,
            timelineOrder = recordedAt,
        )

    private fun timelineRecord(
        id: String,
        recordedAt: ULong,
    ) = TimelineMessageRecordFfi(
        messageIdHex = id,
        sourceMessageIdHex = null,
        direction = "received",
        groupIdHex = "g",
        sender = "s",
        plaintext = "",
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = 9uL,
        tags = emptyList(),
        timelineAt = recordedAt,
        receivedAt = recordedAt,
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
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )

    private fun appMessage(
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
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = recordedAt,
            receivedAt = recordedAt,
        )
}
