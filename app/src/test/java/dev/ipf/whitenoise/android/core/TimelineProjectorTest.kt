package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionKindFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionEmojiFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import dev.ipf.whitenoise.android.core.MessageAttachments
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
    fun projectedMediaKeepsExactCaptionAndDoesNotInventOneForBlankPlaintext() {
        val mediaTag = MessageTagFfi(listOf("imeta", "m application/pdf", "filename report.pdf"))
        val captioned =
            TimelineProjector.toAppMessageRecord(
                timelineRecord(plaintext = "Quarterly report", tags = listOf(mediaTag)),
            )
        val intentionallyBlank =
            TimelineProjector.toAppMessageRecord(
                timelineRecord(plaintext = "", tags = listOf(mediaTag)),
            )

        assertEquals("Quarterly report", captioned.plaintext)
        assertEquals("Quarterly report", MessageProjector.mediaCaption(captioned))
        assertEquals("", intentionallyBlank.plaintext)
        assertNull(MessageProjector.mediaCaption(intentionallyBlank))
    }

    /** MarmotKit 0.10 hands over parsed attachments without the event's imeta tags; the caption must survive. */
    @Test
    fun projectedMediaWithoutImetaTagsStillReadsAsMediaAndKeepsItsCaption() {
        val record =
            TimelineProjector.toAppMessageRecord(
                timelineRecord(
                    plaintext = "Look at this",
                    media =
                        MessageAttachments.acceptedOutcomes(
                            listOf(mediaAttachment(fileName = "photo.jpg", mediaType = "image/jpeg")),
                        ) +
                            MediaAttachmentOutcomeFfi.Rejected(
                                attachmentIndex = 1u,
                                rejection =
                                    MediaAttachmentRejectionFfi(
                                        kind = MediaAttachmentRejectionKindFfi.INVALID_STRUCTURE,
                                        detail = "not an imeta tag",
                                    ),
                            ),
                ),
            )

        assertEquals("Look at this", MessageProjector.mediaCaption(record))
        val markers = record.tags.filter { it.values.firstOrNull() == MessageProjector.ImetaTag }
        assertEquals("one marker per attachment slot, rejected ones included", 2, markers.size)
        assertEquals(listOf("imeta", "m image/jpeg", "filename photo.jpg"), markers[0].values)
        assertEquals(listOf("imeta"), markers[1].values)
    }

    /** A record that still carries its own imeta tags is projected exactly as it came. */
    @Test
    fun projectedMediaThatCarriesItsOwnTagsIsLeftAlone() {
        val ownTag = MessageTagFfi(listOf("imeta", "m application/pdf", "filename report.pdf", "x abc"))
        val record =
            TimelineProjector.toAppMessageRecord(
                timelineRecord(
                    plaintext = "Report",
                    tags = listOf(ownTag),
                    media =
                        MessageAttachments.acceptedOutcomes(
                            listOf(mediaAttachment(fileName = "report.pdf", mediaType = "application/pdf")),
                        ),
                ),
            )
        assertEquals(listOf(ownTag), record.tags)
    }

    /** A typed reply target is restored as the e/q tags the app pairs and navigates by. */
    @Test
    fun projectedReplyWithoutTagsRestoresItsReplyIdentity() {
        val record = TimelineProjector.toAppMessageRecord(timelineRecord(replyToMessageIdHex = "parent"))
        assertEquals("parent", MessageProjector.replyTargetMessageId(record))
        assertEquals(
            listOf(MessageProjector.eventTag("parent"), MessageProjector.quoteTag("parent")),
            record.tags,
        )
        val plain = TimelineProjector.toAppMessageRecord(timelineRecord())
        assertTrue(plain.tags.isEmpty())
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
    fun localPublishFailureKeepsItsBodyAndWarnsDeliveryIsUnconfirmed() {
        // Nothing is known about what the group received, so the tombstone
        // would both overclaim and destroy the only copy of the text (#1747).
        val record = timelineRecord(plaintext = "Secret", invalidationStatus = "local_publish_failed")

        assertEquals("Secret", TimelineProjector.displayBody(record))
        assertEquals("Delivery not confirmed", TimelineProjector.invalidationWarning(record))
    }

    @Test
    fun deletedTakesPrecedenceOverLocalPublishFailure() {
        val record =
            timelineRecord(
                plaintext = "Secret",
                deleted = true,
                deletedByMessageIdHex = "delete-event",
                invalidationStatus = "local_publish_failed",
            )

        assertEquals("Deleted a message", TimelineProjector.displayBody(record))
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
                        media = listOf(secretImageAttachment()),
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

    /** A referenced but unhydrated parent remains a visible unavailable quote. */
    @Test
    fun missingReplyPreviewStillProjectsAnUnavailableQuoteWithoutInventingIdentity() {
        val record =
            timelineRecord(
                plaintext = "Reply body",
                replyPreview = null,
                replyToMessageIdHex = "missing-parent-id",
            )

        assertEquals(
            TimelineReplyDisplay(
                sender = "",
                body = "",
                originalUnavailable = true,
            ),
            TimelineProjector.replyPreview(record),
        )
    }

    /** Messages without a usable parent identifier do not grow a synthetic quote. */
    @Test
    fun recordWithoutAReplyReferenceStillHasNoQuote() {
        assertNull(TimelineProjector.replyPreview(timelineRecord(replyToMessageIdHex = null)))
        assertNull(TimelineProjector.replyPreview(timelineRecord(replyToMessageIdHex = "  ")))
    }

    /** Typed reply attachments preserve filename and MIME metadata through projection. */
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
                mediaFileName = "archive.zip",
                mediaType = "application/zip",
            ),
            TimelineProjector.replyPreview(record),
        )
        assertEquals(
            MediaPreviewFallback(
                filename = "archive.zip",
                kind = ReplyMediaKind.Document,
                mediaType = "application/zip",
            ),
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

    /** Typed APK metadata stays precise while legacy JSON retains its coarse fallback. */
    @Test
    fun typedApkReplyRetainsSafePresentationInputsWhileLegacyJsonStaysCoarse() {
        val typed =
            timelineRecord(
                replyPreview =
                    replyPreview(
                        plaintext = "",
                        media =
                            listOf(
                                mediaAttachment(
                                    fileName = "release.apk",
                                    mediaType = "application/vnd.android.package-archive",
                                ),
                            ),
                    ),
            )
        val legacy =
            timelineRecord(
                replyPreview =
                    replyPreview(
                        plaintext = "",
                        mediaJson = """{"media_type":"application/pdf"}""",
                    ),
            )

        val typedDisplay = TimelineProjector.replyPreview(typed)
        assertEquals("release.apk", typedDisplay?.mediaFileName)
        assertEquals("application/vnd.android.package-archive", typedDisplay?.mediaType)
        assertEquals(ReplyMediaKind.Document, typedDisplay?.mediaKind)

        val legacyDisplay = TimelineProjector.replyPreview(legacy)
        assertEquals(ReplyMediaKind.Document, legacyDisplay?.mediaKind)
        assertNull(legacyDisplay?.mediaFileName)
        assertNull(legacyDisplay?.mediaType)
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
        media = MessageAttachments.acceptedOutcomes(media),
        agentTextStreamJson = null,
        deleted = deleted,
        invalidationStatus = invalidationStatus,
    )

    /** The image attachment several projector cases hide behind a deleted or invalidated reply. */
    private fun secretImageAttachment() = mediaAttachment(fileName = "secret.jpg", mediaType = "image/jpeg")

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

    /** Builds a projected timeline record with independently controllable reply identity. */
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
        tags: List<MessageTagFfi> = emptyList(),
        replyToMessageIdHex: String? = replyPreview?.messageIdHex,
        media: List<MediaAttachmentOutcomeFfi> = emptyList(),
    ) = TimelineMessageRecordFfi(
        messageIdHex = id,
        sourceMessageIdHex = null,
        direction = direction,
        groupIdHex = "group",
        sender = "alice",
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = 9uL,
        tags = tags,
        timelineAt = timelineAt,
        receivedAt = timelineAt,
        replyToMessageIdHex = replyToMessageIdHex,
        replyPreview = replyPreview,
        mediaJson = mediaJson,
        media = media,
        agentTextStreamJson = null,
        groupSystem = null,
        hasReports = false,
        edit = null,
        reactions = reactions,
        deleted = deleted,
        deletedByMessageIdHex = deletedByMessageIdHex,
        invalidationStatus = invalidationStatus,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )
}
