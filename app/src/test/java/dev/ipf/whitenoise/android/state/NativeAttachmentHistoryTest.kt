package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAttachmentHistoryTest {
    @Test
    fun `history lookup preserves source identity and original album index`() {
        val entry = entry(index = 4)
        val request = AttachmentTransferRequest("account", GROUP_ID, DISPLAY_ID, 4, SOURCE_ID)

        val match = requireNotNull(entry.matchingAttachment(request))

        assertEquals(DISPLAY_ID, match.target.displayMessageIdHex)
        assertEquals(SOURCE_ID, match.target.sourceMessageIdHex)
        assertEquals(4, match.target.attachmentIndex)
    }

    @Test
    fun `history lookup rejects a display source or index mismatch`() {
        val entry = entry(index = 4)

        assertNull(entry.matchingAttachment(AttachmentTransferRequest("account", GROUP_ID, "33".repeat(32), 4, SOURCE_ID)))
        assertNull(entry.matchingAttachment(AttachmentTransferRequest("account", GROUP_ID, DISPLAY_ID, 4, "44".repeat(32))))
        assertNull(entry.matchingAttachment(AttachmentTransferRequest("account", GROUP_ID, DISPLAY_ID, 3, SOURCE_ID)))
    }

    private fun entry(index: Int): AttachmentEntryFfi =
        AttachmentEntryFfi(
            messageIdHex = DISPLAY_ID,
            sourceMessageIdHex = SOURCE_ID,
            sender = "sender",
            timelineAt = 2u,
            receivedAt = 3u,
            sourceEpoch = 1u,
            category = AttachmentCategoryFfi.FILE,
            attachment = MediaAttachmentOutcomeFfi.Accepted(index.toUInt(), reference()),
        )

    private fun reference() =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "88".repeat(32),
            plaintextSha256 = "55".repeat(32),
            nonceHex = "77".repeat(12),
            fileName = "file.bin",
            mediaType = "application/octet-stream",
            version = EncryptedMediaVersionFfi.V2,
            sourceEpoch = 1u,
            dim = null,
            thumbhash = null,
        )

    private companion object {
        val GROUP_ID = "aa".repeat(16)
        val DISPLAY_ID = "11".repeat(32)
        val SOURCE_ID = "22".repeat(32)
    }
}
