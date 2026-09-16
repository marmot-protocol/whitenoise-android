package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionKindFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAttachmentsTest {
    private val photo = reference("photo.jpg", "image/jpeg")
    private val notes = reference("notes.pdf", "application/pdf")
    private val rejection = MediaAttachmentRejectionFfi(MediaAttachmentRejectionKindFfi.MALFORMED_FIELD, "bad x")
    private val outcomes =
        listOf(
            MediaAttachmentOutcomeFfi.Accepted(0u, photo),
            MediaAttachmentOutcomeFfi.Rejected(1u, rejection),
            MediaAttachmentOutcomeFfi.Accepted(2u, notes),
        )

    /** Accepted attachments keep the protocol index of their slot, so a rejected sibling never renumbers them. */
    @Test
    fun acceptedKeepsProtocolIndexesAcrossRejectedSlots() {
        assertEquals(listOf(0, 2), MessageAttachments.accepted(outcomes).map { it.index })
        assertEquals(listOf(photo, notes), MessageAttachments.accepted(outcomes).map { it.value })
    }

    /** Rejected slots surface in tag order with their typed rejection. */
    @Test
    fun rejectedReportsSlotAndKind() {
        val rejected = MessageAttachments.rejected(outcomes)
        assertEquals(listOf(1), rejected.map { it.index })
        assertEquals(MediaAttachmentRejectionKindFfi.MALFORMED_FIELD, rejected.single().value.kind)
    }

    /** The compact reference list is for callers that never derive an index from position. */
    @Test
    fun acceptedReferencesDropRejectedSlots() {
        assertEquals(listOf(photo, notes), MessageAttachments.acceptedReferences(outcomes))
        assertTrue(MessageAttachments.hasAccepted(outcomes))
        assertFalse(MessageAttachments.hasAccepted(listOf(MediaAttachmentOutcomeFfi.Rejected(0u, rejection))))
    }

    /** Positional references round-trip through accepted outcomes with consecutive indexes. */
    @Test
    fun acceptedOutcomesIndexPositionally() {
        val wrapped = MessageAttachments.acceptedOutcomes(listOf(photo, notes))
        assertEquals(listOf(0, 1), MessageAttachments.accepted(wrapped).map { it.index })
        assertEquals(MessageAttachments.indexed(listOf(photo, notes)), MessageAttachments.accepted(wrapped))
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
}
