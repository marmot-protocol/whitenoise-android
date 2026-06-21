package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPasteAffordanceTest {
    // bech32 npubs are `npub1` + 58 chars from the bech32 alphabet.
    private val sampleNpub = "npub1" + "a".repeat(58)

    @Test
    fun canOfferPasteForAnyTextMimeType() {
        assertTrue(ClipboardPasteAffordance.canOfferPaste(listOf("text/plain")))
        assertTrue(ClipboardPasteAffordance.canOfferPaste(listOf("image/png", "text/html")))
        assertTrue(ClipboardPasteAffordance.canOfferPaste(listOf("TEXT/URI-LIST")))
        assertFalse(ClipboardPasteAffordance.canOfferPaste(emptyList()))
        assertFalse(ClipboardPasteAffordance.canOfferPaste(listOf("image/png", "application/json")))
    }

    @Test
    fun pasteValueValidatesAtTapTime() {
        val hex = "AB".repeat(32)

        assertEquals(sampleNpub, ClipboardPasteAffordance.pasteValue(" nostr:$sampleNpub "))
        assertEquals(hex.lowercase(), ClipboardPasteAffordance.pasteValue(hex))
        assertNull(ClipboardPasteAffordance.pasteValue("just some notes"))
        assertNull(ClipboardPasteAffordance.pasteValue(hex, allowHexPublicKey = false))
    }
}
