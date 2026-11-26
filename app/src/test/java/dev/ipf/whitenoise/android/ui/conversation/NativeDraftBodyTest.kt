package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.core.nostr.sha256
import dev.ipf.whitenoise.android.core.nostr.toHex
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataScrubber
import dev.ipf.whitenoise.android.media.pdf.pdfFixture
import dev.ipf.whitenoise.android.media.videoFixture
import dev.ipf.whitenoise.android.state.CollectionStoredDraft
import dev.ipf.whitenoise.android.state.DraftStoredFile
import dev.ipf.whitenoise.android.state.NativeDraftBody
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.captureDraftSource
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickBudget
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.removePreparedDocumentMetadata
import dev.ipf.whitenoise.android.ui.conversation.media.removePreparedVideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins that a send names the native draft row it came from by the body the draft holds, whatever send preparation
 * did to the bytes it uploads, so the accepted original can be recognized and removed from the draft.
 */
class NativeDraftBodyTest {
    @get:Rule val folder = TemporaryFolder()

    /** The size and digest the native draft holds for [bytes]. */
    private fun bodyOf(bytes: ByteArray) = NativeDraftBody(bytes.size.toLong(), sha256(bytes).toHex())

    /** An inline native draft attachment under [id]. */
    private fun native(
        id: String,
        bytes: ByteArray,
        mediaType: String,
    ) = PendingAttachment(bytes, mediaType, "name", nativeDraftAttachmentId = id)

    /** A video cleaned for the send still names the tagged body its draft holds, at the draft's size. */
    @Test
    fun scrubbedVideoNamesTheBodyItsDraftHolds() {
        val tagged = videoFixture("mp4-tagged.mp4")
        val draft = native("photo-1", tagged.copyOf(), "video/mp4")

        val sent = checkNotNull(removePreparedVideoMetadata(mapOf("slot" to draft)).ready["slot"])

        assertFalse("the send uploads cleaned bytes", sent.plaintextBytes.contentEquals(tagged))
        assertEquals(bodyOf(tagged), sent.nativeDraftBody)
        val source = checkNotNull(sent.captureDraftSource())
        assertEquals("photo-1", source.attachmentId)
        assertEquals(tagged.size.toLong(), source.byteCount)
        assertFalse(source.fileBacked)
        assertEquals(sha256(tagged).toHex(), source.inlineSha256)
    }

    /** A PDF cleaned on a copy for the send still names the body with metadata that its draft holds. */
    @Test
    fun scrubbedPdfNamesTheBodyItsDraftHolds() {
        val withMetadata = pdfFixture("ghostscript")
        val draft = native("document-1", withMetadata.copyOf(), "application/pdf")

        val sent = checkNotNull(removePreparedDocumentMetadata(mapOf("uri" to draft)).ready["uri"])

        assertEquals(PdfMetadataResult.Clean, PdfMetadataScrubber.inspect(sent.plaintextBytes))
        assertEquals(bodyOf(withMetadata), sent.nativeDraftBody)
        assertEquals(sha256(withMetadata).toHex(), sent.captureDraftSource()?.inlineSha256)
    }

    /** A draft document moved into a staged file for the send keeps the digest of the bytes that left memory. */
    @Test
    fun documentMovedToAFileNamesTheBodyItsDraftHolds() {
        val bytes = ByteArray(64) { it.toByte() }
        val draft = native("document-2", bytes, "application/octet-stream")
        val budget = FileBackedPickBudget(folder.newFolder("sources"), FileBackedSendLimits(1_024, 4_096))

        val staged = stageDraftDocumentToFile(draft, budget).attachments.single()

        try {
            assertTrue(staged.isFileBacked)
            assertEquals(bodyOf(bytes), staged.nativeDraftBody)
            assertEquals(sha256(bytes).toHex(), staged.captureDraftSource()?.inlineSha256)
        } finally {
            staged.sourceFile?.close()
        }
    }

    /** Bytes that are still the draft's own need no remembered body: the source is named from them directly. */
    @Test
    fun untouchedDraftBytesNameTheirOwnBody() {
        val bytes = byteArrayOf(1, 2, 3)
        val draft = native("photo-2", bytes, "image/jpeg")

        assertNull(draft.nativeDraftBody)
        assertEquals(sha256(bytes).toHex(), draft.captureDraftSource()?.inlineSha256)
        assertEquals(3L, draft.captureDraftSource()?.byteCount)
    }

    /** A stored draft file is named by id and size alone, and an attachment outside the draft has no source. */
    @Test
    fun storedFilesAndForeignPicksCarryNoInlineBody() {
        val file = DraftStoredFile("clip", 900L)
        val stored = PendingAttachment(ByteArray(0), "video/mp4", "clip.mp4", draftFile = file)
        val foreign = PendingAttachment(byteArrayOf(4), "image/jpeg", "camera.jpg")

        assertEquals(CollectionStoredDraft("clip", 900L, "video/mp4"), stored.captureDraftSource())
        assertNull(foreign.captureDraftSource())
    }
}
