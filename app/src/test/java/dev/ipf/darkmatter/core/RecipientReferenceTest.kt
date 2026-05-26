package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipientReferenceTest {
    @Test
    fun normalizesProfileLinksNpubsAndHexKeysForGroupRecipients() {
        val hex = "AB".repeat(32)

        assertEquals("npub1abc", RecipientReference.normalize("darkmatter://profile/npub1abc"))
        assertEquals("npub1abc", RecipientReference.normalize("nostr:npub1abc"))
        assertEquals("npub1abc", RecipientReference.normalize(" npub1abc "))
        assertEquals(hex.lowercase(), RecipientReference.normalize(hex))
    }

    @Test
    fun rejectsMalformedRecipientReferencesBeforeGroupCreation() {
        assertNull(RecipientReference.normalize(""))
        assertNull(RecipientReference.normalize("https://example.com/npub1abc"))
        assertNull(RecipientReference.normalize("not-a-public-key"))
        assertNull(RecipientReference.normalize("aa"))
    }

    @Test
    fun tokenizesCommaAndWhitespaceSeparatedRecipientLists() {
        assertEquals(
            listOf("npub1alice", "npub1bob", "npub1carol"),
            RecipientReference.tokenize("npub1alice, npub1bob\nnpub1carol"),
        )
    }
}
