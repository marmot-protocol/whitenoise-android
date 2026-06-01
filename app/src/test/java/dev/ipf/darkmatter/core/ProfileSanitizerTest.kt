package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSanitizerTest {
    @Test
    fun displayNamesCollapseWhitespaceAndStripUnsafeCharacters() {
        val raw = " Alice\n\u202E Admin \u200B "

        assertEquals("Alice Admin", ProfileSanitizer.displayName(raw))
    }

    @Test
    fun imageUrlsOnlyAllowHttpsUrlsWithHosts() {
        assertEquals("https://example.com/avatar.png", ProfileSanitizer.imageUrl(" https://example.com/avatar.png "))
        assertNull(ProfileSanitizer.imageUrl("http://example.com/avatar.png"))
        assertNull(ProfileSanitizer.imageUrl("data:image/png;base64,abc"))
        assertNull(ProfileSanitizer.imageUrl("file:///tmp/avatar.png"))
        assertNull(ProfileSanitizer.imageUrl("https:///missing-host.png"))
    }

    @Test
    fun messageBodyPreservesNormalNewlinesButClampsBlankRuns() {
        assertEquals("one\n\ntwo", ProfileSanitizer.messageBody(" one\n\n\n\ntwo "))
    }
}
