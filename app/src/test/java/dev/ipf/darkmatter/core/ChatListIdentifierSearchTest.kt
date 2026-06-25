package dev.ipf.darkmatter.core

import dev.ipf.darkmatter.core.ChatListIdentifierSearch.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatListIdentifierSearchTest {
    // A bech32-valid-shaped npub: `npub1` + 58 chars from the bech32 alphabet.
    private val sampleNpub = "npub1" + "a".repeat(58)

    @Test
    fun classifiesNpubAndLinkForms() {
        assertEquals(Identifier.Npub(sampleNpub), ChatListIdentifierSearch.classify(sampleNpub))
        assertEquals(Identifier.Npub(sampleNpub), ChatListIdentifierSearch.classify(" $sampleNpub "))
        assertEquals(Identifier.Npub(sampleNpub), ChatListIdentifierSearch.classify("nostr:$sampleNpub"))
        assertEquals(
            Identifier.Npub(sampleNpub),
            ChatListIdentifierSearch.classify("whitenoise://profile/$sampleNpub"),
        )
        assertEquals(
            Identifier.Npub(sampleNpub),
            ChatListIdentifierSearch.classify("whitenoise-staging://profile/$sampleNpub"),
        )
        assertEquals(
            Identifier.Npub(sampleNpub),
            ChatListIdentifierSearch.classify("whitenoise-dev://profile/$sampleNpub"),
        )
    }

    @Test
    fun classifiesNip05Addresses() {
        assertEquals(Identifier.Nip05("alice@example.com"), ChatListIdentifierSearch.classify("alice@example.com"))
        assertEquals(
            Identifier.Nip05("bob@relay.sub.domain.io"),
            ChatListIdentifierSearch.classify("  bob@relay.sub.domain.io  "),
        )
        // The original casing is preserved for display; resolution lowercases.
        assertEquals(Identifier.Nip05("Alice@Example.com"), ChatListIdentifierSearch.classify("Alice@Example.com"))
    }

    @Test
    fun treatsPlainTextAsNoIdentifier() {
        assertNull(ChatListIdentifierSearch.classify(""))
        assertNull(ChatListIdentifierSearch.classify("   "))
        assertNull(ChatListIdentifierSearch.classify("alice"))
        assertNull(ChatListIdentifierSearch.classify("work chat"))
        // No dot in the domain -> not a NIP-05, stays a plain query.
        assertNull(ChatListIdentifierSearch.classify("alice@localhost"))
        // Bare '@' / malformed addresses are plain text, not identifiers.
        assertNull(ChatListIdentifierSearch.classify("@"))
        assertNull(ChatListIdentifierSearch.classify("a@b@c.com"))
        // A short npub-prefixed string is not a valid npub and has no '@'.
        assertNull(ChatListIdentifierSearch.classify("npub1abc"))
    }

    @Test
    fun prefersNpubOverNip05WhenBothCouldMatch() {
        // An npub never contains '@', so this only asserts npub wins for a real
        // npub and that a NIP-05-looking npub is impossible — defensive check
        // that the npub branch is evaluated first.
        val result = ChatListIdentifierSearch.classify(sampleNpub)
        assertEquals(Identifier.Npub(sampleNpub), result)
    }
}
