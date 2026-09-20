package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.state.PendingAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationAttachmentDraftTest {
    /** Native staging preserves every send-relevant field and the stable shelf identity. */
    @Test
    fun pendingAttachmentRoundTripsThroughNativeDraft() {
        val pending =
            PendingAttachment(
                plaintextBytes = byteArrayOf(1, 2, 3),
                mediaType = "video/mp4",
                fileName = "clip.mp4",
                dim = "640x480",
                thumbhash = "thumb",
            )

        val native = pending.toMessageDraftAttachment("stable-id")

        assertEquals("stable-id", native.id)
        assertEquals(pending, native.toPendingAttachment())
        assertTrue(native.isComposerVisual())
    }

    /** Only image/video drafts should return to the visual composer shelf. */
    @Test
    fun nativeDraftClassificationKeepsDocumentsOutOfVisualSlots() {
        val document = attachment(mediaType = "application/pdf")
        val image = attachment(mediaType = "image/png")
        val video = attachment(mediaType = "video/mp4")

        assertFalse(document.isComposerVisual())
        assertTrue(image.isComposerVisual())
        assertTrue(video.isComposerVisual())
    }

    /** Document retry identity is deterministic within one account and conversation. */
    @Test
    fun documentIdentityIsStableButConversationScoped() {
        val uri = Uri.parse("content://picker/document/7")

        val first = stagedDocumentAttachmentId("alice", "group-a", uri)

        assertEquals(first, stagedDocumentAttachmentId("alice", "group-a", uri))
        assertFalse(first == stagedDocumentAttachmentId("alice", "group-b", uri))
    }

    /** Builds a minimal native draft descriptor for classification tests. */
    private fun attachment(mediaType: String) =
        MessageDraftAttachmentFfi(
            id = "id-$mediaType",
            fileName = "draft.bin",
            mediaType = mediaType,
            plaintext = byteArrayOf(9),
            dim = null,
            thumbhash = null,
            durationSeconds = null,
            waveformSamples = emptyList(),
        )
}
