package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataField
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

    /** A PDF already verified as clean goes at once. */
    @Test
    fun aSendWithNothingToDecideGoesAtOnce() {
        var sent = 0
        choices.confirmThen(listOf(cleaned), { error("cancelled") }) { sent++ }

        assertEquals(1, sent)
        assertNull(choices.decision)
    }

    /** A PDF that keeps its metadata waits for an answer, and the answer applies to that PDF only. */
    @Test
    fun aPdfThatKeepsItsMetadataWaitsForAnAnswer() {
        var consent = emptySet<Uri>()
        choices.confirmThen(listOf(cleaned, signed, signed), { error("cancelled") }) { consent = it }

        val decision = checkNotNull(choices.decision)
        assertEquals(listOf("signed.pdf"), decision.names)
        assertEquals(setOf(PdfMetadataRefusal.Signed), decision.reasons)
        assertTrue(consent.isEmpty())

        choices.confirm()

        assertEquals(setOf(signed), consent)
        assertFalse(choices.keepsOriginal(signed))
        assertFalse(choices.keepsOriginal(cleaned))
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
        var consent = emptySet<Uri>()
        choices.confirmThen(listOf(signed), {}) { consent = it }
        choices.confirm()

        assertEquals(setOf(signed), consent)
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
        // A send can arrive before the background read's effect starts.
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

    /** A selection removed during a pending send cancels that send. */
    @Test
    fun aPdfLeavingWhileReadCancelsTheSend() {
        val later = Uri.parse("content://fixture/later")
        var sent = 0
        var cancelled = 0
        choices.reading(later)
        choices.confirmThen(listOf(cleaned), { error("cancelled") }) { sent++ }
        assertEquals("a send without the unread PDF goes at once", 1, sent)
        choices.confirmThen(listOf(cleaned, later), { cancelled++ }) { sent++ }
        assertEquals(1, sent)

        choices.retainOnly(listOf(cleaned))

        assertEquals(1, sent)
        assertEquals(1, cancelled)
    }

    @Test
    fun aChangedPdfCancelsAnOpenSendDecision() {
        var cancelled = 0
        choices.confirmThen(listOf(cleaned, signed), { cancelled++ }) { error("sent") }
        assertNotNull(choices.decision)

        choices.reading(cleaned)
        choices.confirm()

        assertEquals(1, cancelled)
        assertNull(choices.decision)
    }

    @Test
    fun perFileKeepMetadataChoiceStartsOffAndIsClearedWhenTheBodyChanges() {
        val removable = ComposerPdfState("invoice.pdf", PdfMetadataResult.Removable(setOf(PdfMetadataField.Author)))
        choices.record(cleaned, removable)
        var consent = emptySet<Uri>()

        fun send() = choices.confirmThen(listOf(cleaned), { error("cancelled") }) { consent = it }

        send()
        assertTrue(consent.isEmpty())
        choices.toggleKeepMetadata(cleaned)
        send()
        assertEquals(setOf(cleaned), consent)
        choices.reading(cleaned)
        choices.record(cleaned, removable)
        send()
        assertTrue(consent.isEmpty())
    }
}
