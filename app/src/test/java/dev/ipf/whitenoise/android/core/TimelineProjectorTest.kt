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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineProjectorTest {
    @Test
    fun retentionIndicatorRequiresAPositiveDuration() {
        assertTrue(retentionIndicatorVisible(60uL))
        // An explicit zero means retention was disabled for the message.
        assertFalse(retentionIndicatorVisible(0uL))
        assertFalse(retentionIndicatorVisible(null))
    }

    @Test
    fun projectedRecordCarriesRetentionMetadataThrough() {
        val record =
            TimelineProjector.toAppMessageRecord(
                timelineRecord(plaintext = "x").copy(
                    sourceEpoch = 7uL,
                    retentionSeconds = 3600uL,
                    retentionExpiresAt = 999uL,
                ),
            )

        assertEquals(7uL, record.sourceEpoch)
        assertEquals(3600uL, record.retentionSeconds)
        assertEquals(999uL, record.retentionExpiresAt)
    }

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
                        contentTokens =
                            MarkdownDocumentFfi(
                                truncated = false,
                                blocks = emptyList(),
                                blankLinesBefore = ByteArray(0),
                            ),
                        kind = 9uL,
                        mediaJson = null,
                        media = emptyList(),
                        agentTextStreamJson = null,
                        deleted = false,
                        invalidationStatus = null,
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
    fun outgoingLosingBranchKeepsBodyAndUsesPartialVisibilityWarning() {
        val record =
            timelineRecord(
                id = "invalidated",
                plaintext = "Secret",
                invalidationStatus = "LosingBranch",
                direction = "sent",
            )

        assertEquals("Secret", TimelineProjector.displayBody(record))
        assertEquals("May not be visible to everyone", TimelineProjector.invalidationWarning(record))
    }

    @Test
    fun beyondAnchorUsesNonCanonicalHistoryWarning() {
        val record = timelineRecord(invalidationStatus = "BeyondAnchor")

        assertEquals(
            "Not confirmed in the group's current history",
            TimelineProjector.invalidationWarning(record),
        )
    }

    @Test
    fun localPublishFailureKeepsPersistedFailurePresentation() {
        val record = timelineRecord(plaintext = "Secret", invalidationStatus = "local_publish_failed")

        assertEquals("Didn't reach the group", TimelineProjector.displayBody(record))
        assertNull(TimelineProjector.invalidationWarning(record))
    }

    @Test
    fun unknownInvalidationKeepsPersistedFailurePresentation() {
        val record = timelineRecord(plaintext = "Secret", invalidationStatus = "FutureReason")

        assertEquals("Didn't reach the group", TimelineProjector.displayBody(record))
        assertNull(TimelineProjector.invalidationWarning(record))
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
        assertNull(TimelineProjector.invalidationWarning(record))
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
                        invalidationStatus = "LosingBranch",
                        mediaJson = """{"media_type":"image/jpeg"}""",
                        media = listOf(mediaAttachment(fileName = "secret.jpg", mediaType = "image/jpeg")),
                    ),
            )

        assertEquals(
            TimelineReplyDisplay(sender = "alice", body = "Deleted a message"),
            TimelineProjector.replyPreview(record),
        )
    }

    @Test
    fun losingBranchReplyPreviewKeepsBodyAndAddsPartialVisibilityWarning() {
        val record =
            timelineRecord(
                replyPreview =
                    replyPreview(
                        plaintext = "Parent message",
                        invalidationStatus = "LosingBranch",
                    ),
            )

        assertEquals(
            TimelineReplyDisplay(
                sender = "alice",
                body = "Parent message",
                warning = "May not be visible to everyone",
            ),
            TimelineProjector.replyPreview(record),
        )
    }

    @Test
    fun fallbackReplyTargetKeepsBodyAndAddsPartialVisibilityWarning() {
        val target = timelineRecord(plaintext = "Parent message", invalidationStatus = "LosingBranch")

        assertEquals(
            TimelineReplyDisplay(
                sender = "alice",
                body = "Parent message",
                warning = "May not be visible to everyone",
            ),
            TimelineProjector.replyTargetPreview(target),
        )
    }

    @Test
    fun deletedFallbackReplyTargetSuppressesWarningAndMedia() {
        val target =
            timelineRecord(
                plaintext = "Parent message",
                deleted = true,
                invalidationStatus = "LosingBranch",
                mediaJson = """{"media_type":"image/jpeg"}""",
            )

        assertEquals(
            TimelineReplyDisplay(sender = "alice", body = "Deleted a message"),
            TimelineProjector.replyTargetPreview(target),
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
        assertEquals(
            "archive.zip",
            replyBodyWithTypedMediaFallback(
                plaintext = "",
                projectedBody = "File",
                mediaFallback = typedReplyMediaFallback(listOf(attachment)),
                copy = MessageTextCopy.Default,
            ),
        )
        assertEquals(
            "Release bundle",
            replyBodyWithTypedMediaFallback(
                plaintext = "Release bundle",
                projectedBody = "Release bundle",
                mediaFallback = typedReplyMediaFallback(listOf(attachment)),
                copy = MessageTextCopy.Default,
            ),
        )
    }

    @Test
    fun replyPreviewRetainsLegacyMediaKindWhenTypedAttachmentIsUnavailable() {
        val record =
            timelineRecord(
                replyPreview =
                    replyPreview(
                        plaintext = "",
                        mediaJson = """{"media_type":"video/mp4"}""",
                    ),
            )

        assertEquals(
            TimelineReplyDisplay(
                sender = "alice",
                body = "Video",
                mediaKind = ReplyMediaKind.Video,
            ),
            TimelineProjector.replyPreview(record),
        )
    }

    private fun replyPreview(
        plaintext: String,
        kind: ULong = 9uL,
        sender: String = "alice",
        deleted: Boolean = false,
        invalidationStatus: String? = null,
        mediaJson: String? = null,
        media: List<MediaAttachmentReferenceFfi> = emptyList(),
    ) = TimelineReplyPreviewFfi(
        messageIdHex = "parent",
        sender = sender,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = kind,
        mediaJson = mediaJson,
        media = media,
        agentTextStreamJson = null,
        deleted = deleted,
        invalidationStatus = invalidationStatus,
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
        version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
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
        direction: String = "received",
        mediaJson: String? = null,
    ) = TimelineMessageRecordFfi(
        messageIdHex = id,
        sourceMessageIdHex = null,
        direction = direction,
        groupIdHex = "group",
        sender = "alice",
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = 9uL,
        tags = emptyList<MessageTagFfi>(),
        timelineAt = timelineAt,
        receivedAt = timelineAt,
        replyToMessageIdHex = replyPreview?.messageIdHex,
        replyPreview = replyPreview,
        mediaJson = mediaJson,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = reactions,
        deleted = deleted,
        deletedByMessageIdHex = deletedByMessageIdHex,
        invalidationStatus = invalidationStatus,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )
}
