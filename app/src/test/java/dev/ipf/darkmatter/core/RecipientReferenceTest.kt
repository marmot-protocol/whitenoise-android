package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipientReferenceTest {
    // bech32 npubs are `npub1` + 58 chars from the bech32 alphabet.
    private val sampleNpub = "npub1" + "a".repeat(58)

    @Test
    fun normalizesProfileLinksNpubsAndHexKeysForGroupRecipients() {
        val hex = "AB".repeat(32)

        assertEquals(sampleNpub, RecipientReference.normalize("whitenoise://profile/$sampleNpub"))
        assertEquals(sampleNpub, RecipientReference.normalize("whitenoise-staging://profile/$sampleNpub"))
        assertEquals(sampleNpub, RecipientReference.normalize("whitenoise-dev://profile/$sampleNpub"))
        assertEquals(sampleNpub, RecipientReference.normalize("nostr:$sampleNpub"))
        assertEquals(sampleNpub, RecipientReference.normalize(" $sampleNpub "))
        assertEquals(hex.lowercase(), RecipientReference.normalize(hex))
    }

    @Test
    fun rejectsMalformedRecipientReferencesBeforeGroupCreation() {
        assertNull(RecipientReference.normalize(""))
        assertNull(RecipientReference.normalize("https://example.com/$sampleNpub"))
        assertNull(RecipientReference.normalize("not-a-public-key"))
        assertNull(RecipientReference.normalize("aa"))
        // Short npub-prefixed strings used to slip through; they should not now.
        assertNull(RecipientReference.normalize("npub1abc"))
    }

    @Test
    fun tokenizesCommaAndWhitespaceSeparatedRecipientLists() {
        assertEquals(
            listOf("npub1alice", "npub1bob", "npub1carol"),
            RecipientReference.tokenize("npub1alice, npub1bob\nnpub1carol"),
        )
    }

    @Test
    fun acceptsOnlyPublicIdentifierClipboardInput() {
        val hex = "AB".repeat(32)

        assertEquals(sampleNpub, RecipientReference.plausibleClipboardInput("whitenoise://profile/$sampleNpub"))
        assertEquals(hex.lowercase(), RecipientReference.plausibleClipboardInput(hex))
        assertNull(RecipientReference.plausibleClipboardInput("nsec1not-a-public-identifier"))
        assertNull(RecipientReference.plausibleClipboardInput("just some notes"))
        assertNull(RecipientReference.plausibleClipboardInput("  \n  "))
    }

    @Test
    fun canRejectHexClipboardInputForNpubOnlyFields() {
        val hex = "AB".repeat(32)

        assertEquals(sampleNpub, RecipientReference.plausibleClipboardInput(sampleNpub, allowHexPublicKey = false))
        assertNull(RecipientReference.plausibleClipboardInput(hex, allowHexPublicKey = false))
    }
}
