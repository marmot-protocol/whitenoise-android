package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileLinkTest {
    @Test
    fun buildsDarkMatterProfileDeepLinks() {
        val link = ProfileLink("npub1abc")

        assertEquals("darkmatter://profile/npub1abc", link.uri)
    }

    @Test
    fun parsesDarkMatterNostrAndBareNpubPayloads() {
        assertEquals(ProfileLink("npub1abc"), ProfileLink.parse("darkmatter://profile/npub1abc"))
        assertEquals(ProfileLink("npub1abc"), ProfileLink.parse("darkmatter://npub1abc"))
        assertEquals(ProfileLink("npub1abc"), ProfileLink.parse("nostr:npub1abc"))
        assertEquals(ProfileLink("npub1abc"), ProfileLink.parse("  npub1abc  "))
    }

    @Test
    fun rejectsNonProfilePayloads() {
        assertNull(ProfileLink.parse("https://example.com/npub1abc"))
        assertNull(ProfileLink.parse("darkmatter://profile/not-a-profile"))
        assertNull(ProfileLink.parse(""))
    }
}
