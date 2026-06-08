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
    fun imageUrlsRejectPrivateAndLoopbackHosts() {
        // SSRF guard: an avatar URL must not point the app at the device's own
        // loopback or the local network. See issue #89.
        assertNull(ProfileSanitizer.imageUrl("https://127.0.0.1/avatar.png"))
        assertNull(ProfileSanitizer.imageUrl("https://192.168.1.1/avatar.png"))
        assertNull(ProfileSanitizer.imageUrl("https://10.0.0.5:8443/secret.png"))
        assertNull(ProfileSanitizer.imageUrl("https://169.254.1.1/avatar.png"))
        assertNull(ProfileSanitizer.imageUrl("https://[::1]/avatar.png"))
        assertNull(ProfileSanitizer.imageUrl("https://localhost/avatar.png"))
        // Public hosts still pass.
        assertEquals("https://example.com/avatar.png", ProfileSanitizer.imageUrl("https://example.com/avatar.png"))
    }

    @Test
    fun messageBodyPreservesNormalNewlinesButClampsBlankRuns() {
        assertEquals("one\n\ntwo", ProfileSanitizer.messageBody(" one\n\n\n\ntwo "))
    }
}
