package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionKindFfi
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /** A rejected slot stays out of every media bucket but keeps its siblings' protocol indexes intact. */
    @Test
    fun rejectedSlotsRenderAsPlaceholdersWithoutRenumberingSiblings() {
        val rejection = MediaAttachmentRejectionFfi(MediaAttachmentRejectionKindFfi.UNSUPPORTED_FORMAT, "unknown")
        val media =
            bubbleMedia(
                attachments =
                    MessageAttachmentSet(
                        accepted =
                            MessageAttachments.indexed(listOf(reference("first.jpg", "image/jpeg"))) +
                                listOf(IndexedValue(2, reference("notes.pdf", "application/pdf"))),
                        rejected = listOf(IndexedValue(1, rejection)),
                    ),
                pendingAttachments = emptyList(),
            )

        assertEquals(listOf(0), media.images.map { it.index })
        assertEquals(listOf(2), media.files.map { it.index })
        assertEquals(listOf(1), media.rejected.map { it.index })
        assertEquals(
            MediaAttachmentRejectionKindFfi.UNSUPPORTED_FORMAT,
            media.rejected
                .single()
                .value.kind,
        )
        assertTrue(media.hasConfirmedMedia)
    }

    /**
     * A large audio file sent from its staged file has nothing to play until it is published, so it is left to the
     * pending file card, while a small one held in memory stays pending audio.
     */
    @Test
    fun stagedFileAudioIsLeftToThePendingFileCard() {
        val file = File.createTempFile("staged-audio", ".part")
        try {
            val staged =
                PendingAttachment(ByteArray(0), "audio/mpeg", "song.mp3", sourceFile = StagedUploadSource(file, 8L))
            val media = bubbleMedia(emptyList(), listOf(staged, pending("voice.ogg", "audio/ogg")))

            assertEquals(listOf(1), media.pendingAudio.map { it.index })
        } finally {
            file.delete()
        }
    }

    /** A document and a large audio file sent from its staged file are general file cards, other media are not. */
    @Test
    fun stagedFileAudioCountsAsAGeneralFileCard() {
        val file = File.createTempFile("staged-audio", ".part")
        try {
            val staged =
                PendingAttachment(ByteArray(0), "audio/mpeg", "song.mp3", sourceFile = StagedUploadSource(file, 8L))

            assertTrue(isPendingGeneralFile(staged))
            assertTrue(isPendingGeneralFile(pending("notes.pdf", "application/pdf")))
            assertFalse(isPendingGeneralFile(pending("voice.ogg", "audio/ogg")))
            assertFalse(isPendingGeneralFile(pending("clip.mp4", "video/mp4")))
            assertFalse(isPendingGeneralFile(pending("photo.jpg", "image/jpeg")))
        } finally {
            file.delete()
        }
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
    fun fileCardOwnsFooterWhenNoVisualOverlayExists() {
        assertTrue(
            fileCardOwnsFooter(
                deleted = false,
                fileCount = 1,
                visualOwnsFooter = false,
            ),
        )
        assertTrue(
            fileCardOwnsFooter(
                deleted = false,
                fileCount = 3,
                visualOwnsFooter = false,
            ),
        )
    }

    @Test
    fun fileCardDoesNotDuplicateAnotherFooterOwner() {
        assertFalse(
            fileCardOwnsFooter(
                deleted = true,
                fileCount = 1,
                visualOwnsFooter = false,
            ),
        )
        assertFalse(
            fileCardOwnsFooter(
                deleted = false,
                fileCount = 0,
                visualOwnsFooter = false,
            ),
        )
        assertFalse(
            fileCardOwnsFooter(
                deleted = false,
                fileCount = 1,
                visualOwnsFooter = true,
            ),
        )
    }

    @Test
    fun uncaptionedVisualAlbumOwnsTheFooter() {
        assertTrue(
            visualMediaOwnsFooter(
                deleted = false,
                hasInvalidationWarning = false,
                visualCount = 3,
                fileCount = 0,
                hasCaption = false,
            ),
        )
    }

    @Test
    fun visualAlbumDelegatesFooterToCaptionOrWarning() {
        assertFalse(
            visualMediaOwnsFooter(
                deleted = false,
                hasInvalidationWarning = false,
                visualCount = 3,
                fileCount = 0,
                hasCaption = true,
            ),
        )
        assertFalse(
            visualMediaOwnsFooter(
                deleted = false,
                hasInvalidationWarning = true,
                visualCount = 3,
                fileCount = 0,
                hasCaption = false,
            ),
        )
        assertFalse(
            visualMediaOwnsFooter(
                deleted = true,
                hasInvalidationWarning = false,
                visualCount = 3,
                fileCount = 0,
                hasCaption = false,
            ),
        )
    }

    /** Mixed visual/file messages must select the final file card as their sole footer owner. */
    @Test
    fun mixedVisualAndFileGroupDelegatesExactlyOneFooterToTheFileCard() {
        val visualOwnsFooter =
            visualMediaOwnsFooter(
                deleted = false,
                hasInvalidationWarning = false,
                visualCount = 1,
                fileCount = 1,
                hasCaption = false,
            )
        val fileOwnsFooter =
            fileCardOwnsFooter(
                deleted = false,
                fileCount = 1,
                visualOwnsFooter = visualOwnsFooter,
            )

        assertFalse(visualOwnsFooter)
        assertTrue(fileOwnsFooter)
        assertEquals(1, listOf(visualOwnsFooter, fileOwnsFooter).count { it })
    }

    /** A caption is the message's last line: it carries the footer, so neither tile nor card may. */
    @Test
    fun invalidatedCaptionedFileGroupLeavesTheFooterToItsCaption() {
        val visualOwnsFooter =
            visualMediaOwnsFooter(
                deleted = false,
                hasInvalidationWarning = true,
                visualCount = 1,
                fileCount = 2,
                hasCaption = true,
            )
        val fileOwnsFooter =
            fileCardOwnsFooter(
                deleted = false,
                fileCount = 2,
                visualOwnsFooter = visualOwnsFooter,
                hasCaption = true,
            )

        assertFalse(visualOwnsFooter)
        assertFalse(fileOwnsFooter)
    }

    /** Without a caption the last file card keeps the footer; with one, the caption takes it. */
    @Test
    fun captionedFileHandsTheFooterToItsCaption() {
        assertTrue(fileCardOwnsFooter(deleted = false, fileCount = 1, visualOwnsFooter = false, hasCaption = false))
        assertFalse(fileCardOwnsFooter(deleted = false, fileCount = 1, visualOwnsFooter = false, hasCaption = true))
    }

    /** A retained generic file with no confirmed media selects the pending file renderer. */
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

    /** Confirmed, audio, and visual media each suppress the generic pending-file renderer. */
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
