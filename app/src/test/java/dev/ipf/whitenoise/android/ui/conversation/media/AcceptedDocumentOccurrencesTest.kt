package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Equal URI values may represent separate user-selected document occurrences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AcceptedDocumentOccurrencesTest {
    /** Accepted original does not remove copies appended during send. */
    @Test
    fun acceptedOriginalDoesNotRemoveCopiesAppendedDuringSend() {
        val a = Uri.parse("content://documents/A")
        val b = Uri.parse("content://documents/B")
        assertEquals(listOf(a, b), removeAcceptedDocumentOccurrences(listOf(a, a, b), listOf(a)))
        assertEquals(listOf(a, b), removeAcceptedDocumentOccurrences(listOf(a, a, a, b), listOf(a, a)))
    }
}
