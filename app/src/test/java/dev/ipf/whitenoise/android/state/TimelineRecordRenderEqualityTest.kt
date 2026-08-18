package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineRecordRenderEqualityTest {
    @Test
    fun typedMediaChangeInvalidatesRenderedRecord() {
        val withoutMedia = record()
        val withMedia = record().copy(media = listOf(reference()))

        assertFalse(timelineRecordsRenderEqual(withoutMedia, withMedia))
    }

    @Test
    fun observationTimestampChangeDoesNotInvalidateRenderedRecord() {
        val first = record()
        val laterObservation = record().copy(timelineAt = 99uL, receivedAt = 100uL)

        assertTrue(timelineRecordsRenderEqual(first, laterObservation))
    }

    @Test
    fun markdownHydrationInvalidatesRenderedRecord() {
        val withoutTokens = record()
        val withTokens =
            record().copy(
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks =
                            listOf(
                                MarkdownBlockFfi.Paragraph(
                                    listOf(MarkdownInlineFfi.Text("caption")),
                                ),
                            ),
                        blankLinesBefore = byteArrayOf(0),
                    ),
            )

        assertFalse(timelineRecordsRenderEqual(withoutTokens, withTokens))
    }

    @Test
    fun authoritativeRetentionProjectionInvalidatesRenderedRecord() {
        val waiting = record()
        val running =
            record().copy(
                retentionSeconds = 60uL,
                retentionExpiresAt = 120uL,
            )

        assertFalse(timelineRecordsRenderEqual(waiting, running))
    }

    private fun record() =
        TimelineMessageRecordFfi(
            messageIdHex = "message",
            sourceMessageIdHex = "source",
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "caption",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            tags = emptyList(),
            timelineAt = 1uL,
            receivedAt = 2uL,
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

    private fun reference() =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://media.example/blob")),
            ciphertextSha256 = "aa".repeat(32),
            plaintextSha256 = "bb".repeat(32),
            nonceHex = "cc".repeat(12),
            fileName = "photo.jpg",
            mediaType = "image/jpeg",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 5uL,
            dim = null,
            thumbhash = null,
        )
}
