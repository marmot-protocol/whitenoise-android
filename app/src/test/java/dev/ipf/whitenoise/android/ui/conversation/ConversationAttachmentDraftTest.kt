package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.editor.stagedPhotoAttachmentId
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
        val uri = "content://picker/document/7"

        val first = stagedDocumentAttachmentId("alice", "group-a", uri)

        assertEquals(first, stagedDocumentAttachmentId("alice", "group-a", uri))
        assertFalse(first == stagedDocumentAttachmentId("alice", "group-b", uri))
    }

    /** Saved picker selections recover prepared native bytes without reopening dead provider grants. */
    @Test
    fun savedSelectionsReconcileWithNativeDraftBytesAfterProcessRecreation() {
        val account = "alice"
        val group = "group-a"
        val slotId = "saved-video-slot"
        val unreadableDocumentUri = "content://expired-picker/document/7"
        val video =
            attachment(
                id = stagedPhotoAttachmentId(account, group, slotId),
                mediaType = "video/mp4",
                bytes = byteArrayOf(1, 2, 3),
            )
        val document =
            attachment(
                id = stagedDocumentAttachmentId(account, group, unreadableDocumentUri),
                mediaType = "application/pdf",
                bytes = byteArrayOf(4, 5, 6),
            )

        val reconciled =
            reconcilePersistedDraftAttachments(
                accountRef = account,
                groupIdHex = group,
                mediaSlotIds = listOf(slotId),
                documentUriStrings = listOf(unreadableDocumentUri),
                attachments = listOf(video, document),
            )

        assertEquals(
            byteArrayOf(1, 2, 3).toList(),
            reconciled.mediaBySlotId
                .getValue(slotId)
                .plaintext
                .toList(),
        )
        assertEquals(
            byteArrayOf(4, 5, 6).toList(),
            reconciled.documentsByUriString
                .getValue(unreadableDocumentUri)
                .plaintext
                .toList(),
        )
        assertTrue(reconciled.unmatched.isEmpty())
    }

    /** A document-picker image keeps its document shelf and never migrates into visual media. */
    @Test
    fun documentPickerImageReconcilesAsDocument() {
        val account = "alice"
        val group = "group-a"
        val uri = "content://picker/document/image"
        val imageDocument =
            attachment(
                id = stagedDocumentAttachmentId(account, group, uri),
                mediaType = "image/png",
            )

        val reconciled =
            reconcilePersistedDraftAttachments(
                accountRef = account,
                groupIdHex = group,
                mediaSlotIds = emptyList(),
                documentUriStrings = listOf(uri),
                attachments = listOf(imageDocument),
            )

        assertEquals(imageDocument, reconciled.documentsByUriString[uri])
        assertTrue(reconciled.mediaBySlotId.isEmpty())
        assertTrue(reconciled.unmatched.isEmpty())
        assertTrue(imageDocument.isComposerDocument())
    }

    /** A removal fence rejects a late prepare result until the URI is explicitly selected again. */
    @Test
    fun documentRemovalFenceRejectsLatePreparationAndAllowsReselection() {
        val uri = "content://picker/document/late"
        val fence = DraftDocumentRemovalFence()

        fence.updateInputs(emptyList(), listOf(uri))
        fence.recordRemoval(uri)

        assertFalse(fence.canPublish(uri, listOf(uri)))
        fence.updateInputs(listOf(uri), emptyList())
        fence.updateInputs(emptyList(), listOf(uri))
        assertTrue(fence.canPublish(uri, listOf(uri)))
    }

    /** Builds a minimal native draft descriptor for classification tests. */
    private fun attachment(
        mediaType: String,
        id: String = "id-$mediaType",
        bytes: ByteArray = byteArrayOf(9),
    ) = MessageDraftAttachmentFfi(
        id = id,
        fileName = "draft.bin",
        mediaType = mediaType,
        plaintext = bytes,
        dim = null,
        thumbhash = null,
        durationSeconds = null,
        waveformSamples = emptyList(),
    )
}
