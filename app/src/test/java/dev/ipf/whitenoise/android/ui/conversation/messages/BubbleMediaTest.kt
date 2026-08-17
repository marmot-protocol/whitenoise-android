package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.PendingAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleMediaTest {
    @Test
    fun partitionsConfirmedMediaAndPreservesProtocolIndices() {
        val media =
            bubbleMedia(
                mediaReferences =
                    listOf(
                        reference("clip.mp4", "video/mp4"),
                        reference("first.jpg", "image/jpeg"),
                        reference("voice.ogg", "audio/ogg"),
                        reference("notes.pdf", "application/pdf"),
                        reference("second.webp", "image/webp"),
                    ),
                pendingAttachments = emptyList(),
            )

        assertEquals(listOf(1, 4), media.images.map { it.index })
        assertEquals(listOf(2), media.audio.map { it.index })
        assertEquals(listOf(0), media.videos.map { it.index })
        assertEquals(listOf(3), media.files.map { it.index })
        assertEquals(listOf(0, 1, 4), media.visuals.map { it.index })
        assertTrue(media.hasConfirmedMedia)
    }

    @Test
    fun projectsPendingAudioAndVisualsWithoutReorderingTheirIndices() {
        val media =
            bubbleMedia(
                mediaReferences = emptyList(),
                pendingAttachments =
                    listOf(
                        pending("voice.ogg", "audio/ogg"),
                        pending("clip.mp4", "video/mp4", dim = "1280x720"),
                        pending("photo.jpg", "image/jpeg", dim = "640x480", thumbhash = "hash"),
                        pending("notes.pdf", "application/pdf"),
                    ),
            )

        assertEquals(listOf(0), media.pendingAudio.map { it.index })
        assertEquals(listOf(1, 2), media.pendingVisuals.map { it.index })
        assertEquals("clip.mp4", media.pendingVisuals[0].value.fileName)
        assertEquals("1280x720", media.pendingVisuals[0].value.dim)
        assertEquals("hash", media.pendingVisuals[1].value.thumbhash)
        assertFalse(media.hasConfirmedMedia)
    }

    @Test
    fun receivedFileOwnsTimestampWhenNoVisualOverlayExists() {
        assertTrue(
            receivedFileOwnsTimestamp(
                deleted = false,
                mine = false,
                fileCount = 1,
                visualOwnsTimestamp = false,
            ),
        )
        assertTrue(
            receivedFileOwnsTimestamp(
                deleted = false,
                mine = false,
                fileCount = 3,
                visualOwnsTimestamp = false,
            ),
        )
    }

    @Test
    fun receivedFileDoesNotDuplicateAnotherTimestampOwner() {
        assertFalse(
            receivedFileOwnsTimestamp(
                deleted = false,
                mine = true,
                fileCount = 1,
                visualOwnsTimestamp = false,
            ),
        )
        assertFalse(
            receivedFileOwnsTimestamp(
                deleted = true,
                mine = false,
                fileCount = 1,
                visualOwnsTimestamp = false,
            ),
        )
        assertFalse(
            receivedFileOwnsTimestamp(
                deleted = false,
                mine = false,
                fileCount = 0,
                visualOwnsTimestamp = false,
            ),
        )
        assertFalse(
            receivedFileOwnsTimestamp(
                deleted = false,
                mine = false,
                fileCount = 1,
                visualOwnsTimestamp = true,
            ),
        )
    }

    @Test
    fun pendingFilePlaceholderOwnsTheOptimisticFileState() {
        assertTrue(
            shouldShowPendingFilePlaceholder(
                deleted = false,
                hasConfirmedMedia = false,
                pendingAudioCount = 0,
                pendingVisualCount = 0,
                hasPendingMediaMarker = true,
            ),
        )
    }

    @Test
    fun pendingFilePlaceholderNeverDuplicatesAnotherMediaRenderer() {
        assertFalse(
            shouldShowPendingFilePlaceholder(
                deleted = false,
                hasConfirmedMedia = true,
                pendingAudioCount = 0,
                pendingVisualCount = 0,
                hasPendingMediaMarker = true,
            ),
        )
        assertFalse(
            shouldShowPendingFilePlaceholder(
                deleted = false,
                hasConfirmedMedia = false,
                pendingAudioCount = 1,
                pendingVisualCount = 0,
                hasPendingMediaMarker = true,
            ),
        )
        assertFalse(
            shouldShowPendingFilePlaceholder(
                deleted = false,
                hasConfirmedMedia = false,
                pendingAudioCount = 0,
                pendingVisualCount = 1,
                hasPendingMediaMarker = true,
            ),
        )
    }

    private fun reference(
        fileName: String,
        mediaType: String,
    ) = MediaAttachmentReferenceFfi(
        locators = emptyList(),
        ciphertextSha256 = "",
        plaintextSha256 = "",
        nonceHex = "",
        fileName = fileName,
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 7uL,
        dim = null,
        thumbhash = null,
    )

    private fun pending(
        fileName: String,
        mediaType: String,
        dim: String? = null,
        thumbhash: String? = null,
    ) = PendingAttachment(
        plaintextBytes = byteArrayOf(1),
        mediaType = mediaType,
        fileName = fileName,
        dim = dim,
        thumbhash = thumbhash,
    )
}
