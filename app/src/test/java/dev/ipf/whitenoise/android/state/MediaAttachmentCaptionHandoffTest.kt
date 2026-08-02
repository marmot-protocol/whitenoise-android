package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineMediaCaption
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.ui.conversation.messages.timelineMessageBubbleSupplementBody
import dev.ipf.whitenoise.android.ui.conversation.messages.timelineMessageDisplayedBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression for #1783: outbound media captions through optimistic bridge,
 * projected upsert/handoff, bubble display seams, and reload-shaped reads.
 */
class MediaAttachmentCaptionHandoffTest {
    @Test
    fun bareProjectedDisplayBodyFallsBackToFilenameNotBridgeCaption() {
        val projected = mediaProjection(plaintext = "")
        val bridge = mediaBridge(plaintext = "invoice attached")

        assertEquals("Photo", TimelineProjector.displayBody(projected))
        assertNotEquals(bridge.plaintext, TimelineProjector.displayBody(projected))
    }

    @Test
    fun projectedRowDisplayedBodyUsesBoundedHandoffCaption() {
        val projected = mediaProjection(plaintext = "")
        val item = projectedItem(mediaBridge(plaintext = "sunset at the pier"), projected)

        assertEquals(
            "sunset at the pier",
            timelineMessageDisplayedBody(
                item = item,
                record = item.record,
                deleted = false,
                persistedFailure = false,
                editState = null,
                deletedBodyText = "deleted",
                invalidatedBodyText = "invalidated",
                messageTextCopy = MessageTextCopy.Default,
            ),
        )
    }

    @Test
    fun projectedPlaintextWithCaptionIsAuthoritativeForDisplay() {
        val projected = mediaProjection(plaintext = "engine caption")
        val bridge = mediaBridge(plaintext = "stale bridge")
        val reconciled = reconciledTimelineActionRecord(projected, bridge)
        val item = projectedItem(reconciled, projected)

        assertEquals("engine caption", reconciled.plaintext)
        assertEquals(
            "engine caption",
            timelineMessageDisplayedBody(
                item = item,
                record = item.record,
                deleted = false,
                persistedFailure = false,
                editState = null,
                deletedBodyText = "deleted",
                invalidatedBodyText = "invalidated",
                messageTextCopy = MessageTextCopy.Default,
            ),
        )
    }

    @Test
    fun blankProjectionFallsBackToBridgeCaptionInSupplementBody() {
        val projected = mediaProjection(plaintext = "")
        val item = projectedItem(mediaBridge(plaintext = "invoice attached"), projected)

        assertEquals(
            "invoice attached",
            timelineMessageBubbleSupplementBody(
                deleted = false,
                persistedFailure = false,
                displayedBody = "ignored",
                hideForStructuredShare = false,
                mediaPendingName = null,
                anyConfirmedMedia = true,
                editState = null,
                projected = projected,
                actionRecord = item.record,
            ),
        )
    }

    @Test
    fun pendingCaptionHandoffBeforeBridgeNotPlaceholder() {
        val projected = mediaProjection(plaintext = "")
        val pending = pendingMedia(plaintext = "album note", fileName = "a.jpg")
        val reconciled = reconciledTimelineActionRecord(projected, pending)
        val item = projectedItem(reconciled, projected)

        assertEquals("album note", reconciled.plaintext)
        assertEquals(
            "album note",
            timelineMessageBubbleSupplementBody(
                deleted = false,
                persistedFailure = false,
                displayedBody = "ignored",
                hideForStructuredShare = false,
                mediaPendingName = null,
                anyConfirmedMedia = true,
                editState = null,
                projected = projected,
                actionRecord = item.record,
            ),
        )
    }

    @Test
    fun intentionallyBlankCaptionStaysFileOnly() {
        val projected = mediaProjection(plaintext = "")
        val bridge = mediaBridge(plaintext = "")
        val reconciled = reconciledTimelineActionRecord(projected, bridge)
        val item = projectedItem(reconciled, projected)

        assertEquals("", reconciled.plaintext)
        assertNull(MessageProjector.copyableText(reconciled))
        assertNull(
            timelineMessageBubbleSupplementBody(
                deleted = false,
                persistedFailure = false,
                displayedBody = "filename",
                hideForStructuredShare = false,
                mediaPendingName = null,
                anyConfirmedMedia = true,
                editState = null,
                projected = projected,
                actionRecord = item.record,
            ),
        )
    }

    @Test
    fun multiAttachmentMessageKeepsOneLogicalCaption() {
        val projected =
            mediaProjection(
                plaintext = "one caption for the album",
                attachmentCount = 3,
            )
        val bridge = mediaBridge(plaintext = "one caption for the album", attachmentCount = 3)
        val reconciled = reconciledTimelineActionRecord(projected, bridge)

        assertEquals("one caption for the album", reconciled.plaintext)
        assertEquals(3, reconciled.tags.size)
    }

    @Test
    fun documentSendUsesSameCaptionHandoffAsImage() {
        val projected =
            mediaProjection(
                plaintext = "",
                mime = "application/pdf",
                fileName = "contract.pdf",
            )
        val bridge =
            mediaBridge(
                plaintext = "signed copy",
                mime = "application/pdf",
                fileName = "contract.pdf",
            )
        val reconciled = reconciledTimelineActionRecord(projected, bridge)
        val item = projectedItem(reconciled, projected)

        assertEquals("signed copy", reconciled.plaintext)
        assertEquals(
            "signed copy",
            timelineMessageBubbleSupplementBody(
                deleted = false,
                persistedFailure = false,
                displayedBody = "ignored",
                hideForStructuredShare = false,
                mediaPendingName = null,
                anyConfirmedMedia = true,
                editState = null,
                projected = projected,
                actionRecord = item.record,
            ),
        )
    }

    @Test
    fun positionSettlementRefreshReusesPriorReconciledCaption() {
        val projected = mediaProjection(plaintext = "")
        val bridge = mediaBridge(plaintext = "still here after refresh")
        val first = reconciledTimelineActionRecord(projected, bridge)
        val afterRefresh = reconciledTimelineActionRecord(projected, first)

        assertEquals("still here after refresh", afterRefresh.plaintext)
    }

    @Test
    fun pendingPlaceholderDoesNotSubstituteForCaption() {
        val projected = mediaProjection(plaintext = "")
        val pending = pendingMedia(plaintext = "📎 scan.pdf", fileName = "scan.pdf")

        assertEquals("", reconciledTimelineActionRecord(projected, pending).plaintext)
    }

    @Test
    fun convergenceInvalidatedProjectionStillReconcilesCaption() {
        val projected =
            mediaProjection(
                plaintext = "",
                invalidationStatus = "LosingBranch",
            )
        val bridge = mediaBridge(plaintext = "local-only caption")
        val reconciled = reconciledTimelineActionRecord(projected, bridge)

        assertEquals("local-only caption", reconciled.plaintext)
    }

    @Test
    fun freshReloadWithProjectionCaptionNeedsNoOptimisticState() {
        val projected = mediaProjection(plaintext = "persisted caption")
        val reloadAction = reconciledTimelineActionRecord(projected, priorActionRecord = null)
        val item = projectedItem(reloadAction, projected)

        assertEquals("persisted caption", reloadAction.plaintext)
        assertEquals(
            "persisted caption",
            timelineMessageBubbleSupplementBody(
                deleted = false,
                persistedFailure = false,
                displayedBody = "ignored",
                hideForStructuredShare = false,
                mediaPendingName = null,
                anyConfirmedMedia = true,
                editState = null,
                projected = projected,
                actionRecord = item.record,
            ),
        )
    }

    @Test
    fun freshReloadWithBlankProjectionCannotRecoverCaptionWithoutPriorState() {
        val projected = mediaProjection(plaintext = "")
        val reloadAction = reconciledTimelineActionRecord(projected, priorActionRecord = null)

        assertEquals("", reloadAction.plaintext)
        assertNull(
            TimelineMediaCaption.handoffPlaintext(projected, reloadAction),
        )
    }

    // ---- helpers ------------------------------------------------------------

    private fun projectedItem(
        actionRecord: AppMessageRecordFfi,
        projected: TimelineMessageRecordFfi,
    ): TimelineMessage =
        TimelineMessage(
            id = "msg:${projected.messageIdHex}",
            record = actionRecord,
            status = MessageStatus.Sent,
            projected = projected,
        )

    private fun pendingMedia(
        plaintext: String,
        fileName: String,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = "temp",
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = plaintext,
            contentTokens = emptyMarkdown(),
            kind = 9uL,
            tags = listOf(MessageTagFfi(listOf("_media_pending", fileName, "image/jpeg"))),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private fun mediaBridge(
        plaintext: String,
        mime: String = "image/jpeg",
        fileName: String = "photo.jpg",
        attachmentCount: Int = 1,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = "confirmed",
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = plaintext,
            contentTokens = emptyMarkdown(),
            kind = 9uL,
            tags = imetaTags(mime, fileName, attachmentCount),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private fun mediaProjection(
        plaintext: String,
        mime: String = "image/jpeg",
        fileName: String = "photo.jpg",
        attachmentCount: Int = 1,
        invalidationStatus: String? = null,
    ): TimelineMessageRecordFfi =
        TimelineMessageRecordFfi(
            messageIdHex = "confirmed",
            sourceMessageIdHex = "event-id",
            direction = "sent",
            groupIdHex = "group",
            sender = "alice",
            plaintext = plaintext,
            contentTokens = emptyMarkdown(),
            kind = 9uL,
            tags = imetaTags(mime, fileName, attachmentCount),
            timelineAt = 1uL,
            receivedAt = 1uL,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = mediaRefs(mime, fileName, attachmentCount),
            agentTextStreamJson = null,
            groupSystem = null,
            reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
            deleted = false,
            deletedByMessageIdHex = null,
            invalidationStatus = invalidationStatus,
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
        )

    private fun imetaTags(
        mime: String,
        fileName: String,
        count: Int,
    ): List<MessageTagFfi> =
        (1..count).map { index ->
            MessageTagFfi(
                listOf(
                    "imeta",
                    "url https://example/$fileName-$index",
                    "m $mime",
                    "filename $fileName-$index",
                ),
            )
        }

    private fun mediaRefs(
        mime: String,
        fileName: String,
        count: Int,
    ): List<MediaAttachmentReferenceFfi> =
        (0 until count).map { index ->
            MediaAttachmentReferenceFfi(
                locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://example/$fileName-$index")),
                ciphertextSha256 = "b".repeat(64),
                plaintextSha256 = "a".repeat(64),
                nonceHex = "c".repeat(24),
                fileName = "$fileName-$index",
                mediaType = mime,
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = 0uL,
                dim = null,
                thumbhash = null,
            )
        }

    private fun emptyMarkdown(): MarkdownDocumentFfi =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = emptyList(),
            blankLinesBefore = ByteArray(0),
        )
}
