package dev.ipf.darkmatter.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the cap-mismatch regression: when the UI accepts an album whose
 * summed plaintext exceeds [ConversationController.MEDIA_RETAINED_MAX_BYTES],
 * `RetainedMediaUpload` evicts itself out of the LRU during its own `put()`,
 * breaking the retry path. The preflight in `sendAttachments()` must reject
 * the album before the optimistic bubble is written — this test pins the
 * arithmetic that powers that check.
 */
class MediaAlbumPreflightTest {
    @Test
    fun emptyAlbum_doesNotExceedCap() {
        assertFalse(ConversationController.albumExceedsRetainedCap(emptyList()))
    }

    @Test
    fun singleSmallAttachment_doesNotExceedCap() {
        val attachments =
            listOf(
                PendingAttachment(
                    plaintextBytes = ByteArray(1024) { 0x01 },
                    mediaType = "image/jpeg",
                    fileName = "a.jpg",
                ),
            )
        assertFalse(ConversationController.albumExceedsRetainedCap(attachments))
    }

    @Test
    fun singleAttachmentExactlyAtCap_doesNotExceedCap() {
        val cap = ConversationController.MEDIA_RETAINED_MAX_BYTES.toInt()
        val attachments =
            listOf(
                PendingAttachment(
                    plaintextBytes = ByteArray(cap) { 0x02 },
                    mediaType = "application/pdf",
                    fileName = "exact.pdf",
                ),
            )
        // strict-greater-than: exactly at cap survives, one byte over fails.
        assertFalse(ConversationController.albumExceedsRetainedCap(attachments))
    }

    @Test
    fun singleAttachmentOneByteOverCap_exceedsCap() {
        val cap = ConversationController.MEDIA_RETAINED_MAX_BYTES.toInt() + 1
        val attachments =
            listOf(
                PendingAttachment(
                    plaintextBytes = ByteArray(cap) { 0x03 },
                    mediaType = "application/pdf",
                    fileName = "over.pdf",
                ),
            )
        assertTrue(ConversationController.albumExceedsRetainedCap(attachments))
    }

    @Test
    fun manyAttachmentsSummingPastCap_exceedsCap() {
        // Two halves-of-cap-plus-one in series exceeds without any single
        // attachment exceeding — the exact mode the document picker enables
        // with `OpenMultipleDocuments`. The fan-out helper that catches the
        // single-file case would NOT catch this without the sum check.
        val halfPlusOne = (ConversationController.MEDIA_RETAINED_MAX_BYTES / 2).toInt() + 1
        val attachments =
            listOf(
                PendingAttachment(
                    plaintextBytes = ByteArray(halfPlusOne) { 0x04 },
                    mediaType = "application/pdf",
                    fileName = "a.pdf",
                ),
                PendingAttachment(
                    plaintextBytes = ByteArray(halfPlusOne) { 0x05 },
                    mediaType = "application/pdf",
                    fileName = "b.pdf",
                ),
            )
        assertTrue(ConversationController.albumExceedsRetainedCap(attachments))
    }

    @Test
    fun manyAttachmentsSummingExactlyAtCap_doesNotExceedCap() {
        val quarter = (ConversationController.MEDIA_RETAINED_MAX_BYTES / 4).toInt()
        val attachments =
            List(4) { idx ->
                PendingAttachment(
                    plaintextBytes = ByteArray(quarter) { idx.toByte() },
                    mediaType = "image/jpeg",
                    fileName = "q$idx.jpg",
                )
            }
        // 4 * (cap / 4) == cap exactly, divisible without remainder.
        assertFalse(ConversationController.albumExceedsRetainedCap(attachments))
    }
}
