package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaestroShareDraftProofTest {
    private val first = MaestroExpectedShareAttachment("alice-document-1", "document.md", "text/markdown")
    private val second = MaestroExpectedShareAttachment("alice-document-2", "table.csv", "text/csv")

    @Test
    fun fileOnlyStagingRequiresEmptyTextAndExactNativeAttachments() {
        val expected = listOf(first, second)
        assertTrue(maestroShareDraftMatches(draft(expected), "group", null, expected))
        assertFalse(maestroShareDraftMatches(null, "group", null, expected))
        assertFalse(maestroShareDraftMatches(draft(emptyList()), "group", null, expected))
        assertFalse(maestroShareDraftMatches(draft(expected, "unexpected"), "group", null, expected))
    }

    @Test
    fun captionRetainsExactWhitespaceAndUnicodeAlongsideAttachments() {
        val text = "  café 👋\nsecond line  "
        assertTrue(maestroShareDraftMatches(draft(listOf(first), text), "group", text, listOf(first)))
        assertFalse(maestroShareDraftMatches(draft(listOf(first), text.trim()), "group", text, listOf(first)))
        assertFalse(maestroShareDraftMatches(draft(emptyList(), text), "group", text, listOf(first)))
    }

    @Test
    fun cancellationAndLastAttachmentRemovalRequireAnAbsentNativeDraft() {
        assertTrue(maestroShareDraftMatches(null, "group", null, emptyList()))
        assertFalse(maestroShareDraftMatches(draft(emptyList()), "group", null, emptyList()))
        assertFalse(maestroShareDraftMatches(draft(listOf(first)), "group", null, emptyList()))
        assertFalse(maestroShareDraftMatches(draft(emptyList(), "other"), "group", null, emptyList()))
    }

    @Test
    fun textOnlyStagingAndCaptionAfterRemovalRejectResidualAttachments() {
        assertTrue(maestroShareDraftMatches(draft(emptyList(), "caption"), "group", "caption", emptyList()))
        assertFalse(maestroShareDraftMatches(draft(listOf(first), "caption"), "group", "caption", emptyList()))
        assertFalse(maestroShareDraftMatches(null, "group", "caption", emptyList()))
        val original = draft(emptyList(), "caption")
        for (invalid in listOf(original.copy(groupIdHex = "other"), original.copy(replyToMessageIdHex = "reply"))) {
            assertFalse(maestroShareDraftMatches(invalid, "group", "caption", emptyList()))
        }
    }

    @Test
    fun wrongGroupReplyOrderDuplicatesAndMissingFilesFailProof() {
        val expected = listOf(first, second)
        val original = draft(expected)
        val invalid =
            listOf(
                original.copy(groupIdHex = "other"),
                original.copy(replyToMessageIdHex = "unintended-reply"),
                draft(expected.reversed()),
                draft(listOf(first, first)),
                draft(listOf(first)),
                draft(expected + first),
            )
        invalid.forEach { assertFalse(maestroShareDraftMatches(it, "group", null, expected)) }
    }

    @Test
    fun wrongOwnerMetadataOrBytesFailEvenWithCorrectAttachmentCount() {
        val original = attachment(first)
        val invalid =
            listOf(
                original.copy(id = "bob-document-1"),
                original.copy(fileName = "renamed.md"),
                original.copy(mediaType = "application/octet-stream"),
                original.copy(plaintext = byteArrayOf(3, 2, 1)),
                original.copy(plaintext = byteArrayOf(1, 2)),
                original.copy(dim = "10x20"),
                original.copy(thumbhash = "unexpected"),
                original.copy(durationSeconds = 1.0),
                original.copy(waveformSamples = listOf(1.0)),
            )
        invalid.forEach {
            val mutated = draft(listOf(first)).copy(mediaAttachments = listOf(it))
            assertFalse(maestroShareDraftMatches(mutated, "group", null, listOf(first)))
        }
    }

    private fun draft(
        expected: List<MaestroExpectedShareAttachment>,
        text: String = "",
    ) = MessageDraftFfi(
        groupIdHex = "group",
        content = text,
        replyToMessageIdHex = null,
        mediaAttachments = expected.map(::attachment),
        createdAtMs = 1,
        updatedAtMs = 2,
    )

    private fun attachment(expected: MaestroExpectedShareAttachment) =
        MessageDraftAttachmentFfi(
            id = expected.id,
            fileName = expected.name,
            mediaType = expected.mime,
            plaintext = byteArrayOf(1, 2, 3),
            dim = null,
            thumbhash = null,
            durationSeconds = null,
            waveformSamples = emptyList(),
        )
}
