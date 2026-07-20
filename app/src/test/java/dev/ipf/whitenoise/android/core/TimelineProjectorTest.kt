package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
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
                        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
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

    @Test
    fun replyPreviewNeutralizesUnstructuredGroupSystemJson() {
        val actorHex = "a1".repeat(32)
        val groupSystemJson =
            """{"v":1,"system_type":"group_avatar_changed","text":"Group avatar changed",""" +
                """"data":{"actor":"$actorHex"}}"""
        val record =
            timelineRecord(
                replyPreview =
                    replyPreview(
                        plaintext = groupSystemJson,
                        kind = 1210uL,
                    ),
            )

        assertEquals(
            TimelineReplyDisplay(sender = "alice", body = "Group updated"),
            TimelineProjector.replyPreview(record),
        )
    }

    @Test
    fun replyPreviewFramesReactionEmoji() {
        val record =
            timelineRecord(
                replyPreview =
                    replyPreview(
                        plaintext = "👍",
                        kind = 7uL,
                    ),
            )

        assertEquals(
            TimelineReplyDisplay(sender = "alice", body = "Reacted 👍"),
            TimelineProjector.replyPreview(record),
        )
    }

    @Test
    fun replyPreviewUsesDeletedCopyForDeleteKind() {
        val record =
            timelineRecord(
                replyPreview =
                    replyPreview(
                        plaintext = "forged delete body",
                        kind = 5uL,
                    ),
            )

        assertEquals(
            TimelineReplyDisplay(sender = "alice", body = "Deleted a message"),
            TimelineProjector.replyPreview(record),
        )
    }

    @Test
    fun deletedReplyPreviewUsesDeletedCopy() {
        val record =
            timelineRecord(
                replyPreview =
                    replyPreview(
                        plaintext = "Parent message",
                        kind = 9uL,
                        deleted = true,
                    ),
            )

        assertEquals(
            TimelineReplyDisplay(sender = "alice", body = "Deleted a message"),
            TimelineProjector.replyPreview(record),
        )
    }

    @Test
    fun replyPreviewUsesTypedAttachmentForDocumentIconAndFilename() {
        val attachment = mediaAttachment(fileName = "archive.zip", mediaType = "application/zip")
        val record =
            timelineRecord(
                replyPreview =
                    replyPreview(
                        plaintext = "",
                        mediaJson = """{"thumbnail_type":"video/mp4"}""",
                        media = listOf(attachment),
                    ),
            )

        assertEquals(
            TimelineReplyDisplay(
                sender = "alice",
                body = "archive.zip",
                mediaKind = ReplyMediaKind.Document,
            ),
            TimelineProjector.replyPreview(record),
        )
        assertEquals(
            MediaPreviewFallback(filename = "archive.zip", kind = ReplyMediaKind.Document),
            typedReplyMediaFallback(listOf(attachment)),
        )
    }

    private fun replyPreview(
        plaintext: String,
        kind: ULong = 9uL,
        sender: String = "alice",
        deleted: Boolean = false,
        mediaJson: String? = null,
        media: List<MediaAttachmentReferenceFfi> = emptyList(),
    ) = TimelineReplyPreviewFfi(
        messageIdHex = "parent",
        sender = sender,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = kind,
        mediaJson = mediaJson,
        media = media,
        agentTextStreamJson = null,
        deleted = deleted,
    )

    private fun mediaAttachment(
        fileName: String,
        mediaType: String,
    ) = MediaAttachmentReferenceFfi(
        locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://media.example/blob")),
        ciphertextSha256 = "aa".repeat(32),
        plaintextSha256 = "bb".repeat(32),
        nonceHex = "cc".repeat(24),
        fileName = fileName,
        mediaType = mediaType,
        version = "encrypted-media-v1",
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )

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
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
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
