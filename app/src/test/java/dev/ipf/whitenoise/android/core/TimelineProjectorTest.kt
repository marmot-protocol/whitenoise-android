package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionEmojiFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineProjectorTest {
    @Test
    fun projectedRecordProvidesBodyReplyPreviewAndReactionTallies() {
        val record =
            timelineRecord(
                plaintext = "Current message",
                replyPreview =
                    TimelineReplyPreviewFfi(
                        messageIdHex = "parent",
                        sender = "alice",
                        plaintext = "Parent message",
                        contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                        kind = 9uL,
                        mediaJson = null,
                        media = emptyList(),
                        agentTextStreamJson = null,
                        deleted = false,
                    ),
                reactions =
                    TimelineReactionSummaryFfi(
                        byEmoji =
                            listOf(
                                TimelineReactionEmojiFfi("👍", 2u, listOf("bob", "carol")),
                                TimelineReactionEmojiFfi("🔥", 1u, listOf("alice")),
                            ),
                        userReactions = emptyList(),
                    ),
            )

        assertEquals("Current message", TimelineProjector.displayBody(record))
        assertEquals(TimelineReplyDisplay(sender = "alice", body = "Parent message"), TimelineProjector.replyPreview(record))
        assertEquals(
            listOf(
                ReactionTally(emoji = "👍", count = 2, mine = true),
                ReactionTally(emoji = "🔥", count = 1, mine = false),
            ),
            TimelineProjector.reactionTallies(record, myAccountId = "bob"),
        )
    }

    @Test
    fun deletedProjectedRecordUsesDeletedCopyAndCanStillBecomeActionRecord() {
        val record =
            timelineRecord(
                id = "deleted",
                plaintext = "Secret",
                timelineAt = 42uL,
                deleted = true,
                deletedByMessageIdHex = "delete-event",
            )

        assertEquals("Deleted a message", TimelineProjector.displayBody(record))

        val actionRecord = TimelineProjector.toAppMessageRecord(record)
        assertEquals("deleted", actionRecord.messageIdHex)
        assertEquals("group", actionRecord.groupIdHex)
        assertEquals(42uL, actionRecord.recordedAt)
    }

    @Test
    fun invalidatedProjectedRecordUsesInvalidatedCopy() {
        val record =
            timelineRecord(
                id = "invalidated",
                plaintext = "Secret",
                invalidationStatus = "LosingBranch",
            )

        assertEquals("Didn't reach the group", TimelineProjector.displayBody(record))
    }

    @Test
    fun deletedTakesPrecedenceOverInvalidation() {
        val record =
            timelineRecord(
                id = "both",
                plaintext = "Secret",
                deleted = true,
                deletedByMessageIdHex = "delete-event",
                invalidationStatus = "LosingBranch",
            )

        assertEquals("Deleted a message", TimelineProjector.displayBody(record))
    }

    private fun timelineRecord(
        id: String = "message",
        plaintext: String = "hello",
        timelineAt: ULong = 1uL,
        replyPreview: TimelineReplyPreviewFfi? = null,
        reactions: TimelineReactionSummaryFfi = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted: Boolean = false,
        deletedByMessageIdHex: String? = null,
        invalidationStatus: String? = null,
    ) = TimelineMessageRecordFfi(
        messageIdHex = id,
        sourceMessageIdHex = null,
        direction = "received",
        groupIdHex = "group",
        sender = "alice",
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
        kind = 9uL,
        tags = emptyList<MessageTagFfi>(),
        timelineAt = timelineAt,
        receivedAt = timelineAt,
        replyToMessageIdHex = replyPreview?.messageIdHex,
        replyPreview = replyPreview,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = reactions,
        deleted = deleted,
        deletedByMessageIdHex = deletedByMessageIdHex,
        invalidationStatus = invalidationStatus,
    )
}
