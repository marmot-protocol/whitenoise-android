package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
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

    /** A send takes the choice for itself, so the next send of the same PDF asks again; it leaves with the PDF. */
    @Test
    fun theChoiceCoversOneSendAndLeavesWithThePdf() {
        choices.confirmThen(listOf(signed), {}) {}
        choices.confirm()

        assertEquals(setOf(signed), choices.takeConsent())
        assertFalse(choices.keepsOriginal(signed))
        choices.confirmThen(listOf(signed), {}) { error("sent without asking") }
        assertNotNull("the next send asks again", choices.decision)
        choices.cancel()

        choices.retainOnly(listOf(cleaned))
        assertNull(choices.state(signed))
        assertNotNull(choices.state(cleaned))
    }

    /** A send that names a PDF still being read waits for it, then asks; leaving the shelf cancels it. */
    @Test
    fun aSendWaitsForAPdfStillBeingRead() {
        val later = Uri.parse("content://fixture/later")
        var sent = 0
        var cancelled = 0
        choices.reading(later)
        choices.confirmThen(listOf(cleaned, later), { cancelled++ }) { sent++ }
        assertEquals(0, sent)
        assertNull(choices.decision)

        choices.record(later, ComposerPdfState("later.pdf", PdfMetadataResult.Refused(PdfMetadataRefusal.Encrypted)))

        assertEquals(listOf("later.pdf"), checkNotNull(choices.decision).names)
        choices.cancel()
        assertEquals(1, cancelled)

        choices.reading(later)
        choices.confirmThen(listOf(later), { cancelled++ }) { sent++ }
        choices.cancel()
        assertEquals(2, cancelled)
        choices.stopReading(later)
        assertEquals(0, sent)
    }

    /** A PDF that leaves the shelf while it is read no longer holds a send back. */
    @Test
    fun aPdfLeavingWhileReadReleasesTheSend() {
        val later = Uri.parse("content://fixture/later")
        var sent = 0
        choices.reading(later)
        choices.confirmThen(listOf(cleaned), { error("cancelled") }) { sent++ }
        assertEquals("a send without the unread PDF goes at once", 1, sent)
        choices.confirmThen(listOf(cleaned, later), { error("cancelled") }) { sent++ }
        assertEquals(1, sent)

        choices.retainOnly(listOf(cleaned))

        assertEquals(2, sent)
    }
}
