package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.state.PendingAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The composer's choice to send PDFs that keep their metadata as they are (#3377). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerPdfMetadataChoicesTest {
    private val signed = Uri.parse("content://fixture/signed")
    private val cleaned = Uri.parse("content://fixture/cleaned")
    private val choices =
        ComposerPdfMetadataChoices().apply {
            record(signed, ComposerPdfState("signed.pdf", PdfMetadataResult.Refused(PdfMetadataRefusal.Signed)))
            record(cleaned, ComposerPdfState("cleaned.pdf", PdfMetadataResult.Clean))
        }

    /** Cleaned and unread PDFs never stop a send. */
    @Test
    fun aSendWithNothingToDecideGoesAtOnce() {
        var sent = 0
        choices.confirmThen(listOf(cleaned, Uri.parse("content://fixture/unread")), { error("cancelled") }) { sent++ }

        assertEquals(1, sent)
        assertNull(choices.decision)
    }

    /** A PDF that keeps its metadata waits for an answer, and the answer applies to that PDF only. */
    @Test
    fun aPdfThatKeepsItsMetadataWaitsForAnAnswer() {
        var sent = 0
        choices.confirmThen(listOf(cleaned, signed, signed), { error("cancelled") }) { sent++ }

        val decision = checkNotNull(choices.decision)
        assertEquals(listOf("signed.pdf"), decision.names)
        assertEquals(setOf(PdfMetadataRefusal.Signed), decision.reasons)
        assertEquals(0, sent)

        choices.confirm()

        assertEquals(1, sent)
        assertTrue(choices.keepsOriginal(signed))
        assertFalse(choices.keepsOriginal(cleaned))
        choices.confirmThen(listOf(signed), { error("cancelled") }) { sent++ }
        assertEquals(2, sent)
    }

    /** Cancelling keeps the send on the shelf and records nothing. */
    @Test
    fun cancellingRecordsNothing() {
        var cancelled = 0
        choices.confirmThen(listOf(signed), { cancelled++ }) { error("sent") }

        choices.cancel()

        assertEquals(1, cancelled)
        assertFalse(choices.keepsOriginal(signed))
        assertNull(choices.decision)
    }

    /** The choice rides on the PDF's own prepared attachment and is forgotten once the PDF leaves the shelf. */
    @Test
    fun theChoiceFollowsThePdfAndLeavesWithIt() {
        choices.confirmThen(listOf(signed), {}) {}
        choices.confirm()
        val prepared =
            mapOf(
                signed to PendingAttachment(byteArrayOf(1), "application/pdf", "signed.pdf"),
                cleaned to PendingAttachment(byteArrayOf(2), "application/pdf", "cleaned.pdf"),
            )

        val marked = choices.marked(prepared)

        assertTrue(marked.getValue(signed).keepsPdfMetadata)
        assertFalse(marked.getValue(cleaned).keepsPdfMetadata)
        choices.retainOnly(listOf(cleaned))
        assertFalse(choices.keepsOriginal(signed))
        assertNull(choices.state(signed))
        assertNotNull(choices.state(cleaned))
    }
}
