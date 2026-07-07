package dev.ipf.whitenoise.android.core

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
    fun displayNamesFoldFullwidthAndCompatibilityHomoglyphs() {
        // NFKC folds fullwidth/compatibility look-alikes so a spoofed
        // "Ａdmin" / "ﬁnance" can't masquerade as the canonical form.
        assertEquals("Admin", ProfileSanitizer.displayName("Ａｄｍｉｎ")) // fullwidth "Admin"
        assertEquals("finance", ProfileSanitizer.displayName("ﬁnance")) // ﬁ ligature
    }

    @Test
    fun displayNamesStripExtraInvisibleFormatCharsButKeepEmojiJoiners() {
        // Soft hyphen / word joiner / invisible operators are spoofing noise.
        assertEquals("Alice", ProfileSanitizer.displayName("Al­i⁠ce"))
        // ZWJ emoji sequence (man + ZWJ + laptop) must survive intact.
        val zwjEmoji = "👨‍💻"
        assertEquals(zwjEmoji, ProfileSanitizer.displayName(zwjEmoji))
    }

    @Test
    fun displayNamesStripCombiningJoinerAndInvisibleMathChars() {
        // Combining grapheme joiner (U+034F), Mongolian vowel separator
        // (U+180E), and the invisible math operators (U+2061..U+2064) are all
        // default-ignorable padding the sanitizer must remove.
        assertEquals("Alice", ProfileSanitizer.displayName("A͏l᠎i⁡c⁤e"))
    }

    @Test
    fun displayNamesCapCombiningMarksPerBaseCharacter() {
        // One base letter plus a long run of Mn accents is 80 code points and
        // passes safeTake, but must not render as an unbounded Zalgo cluster.
        val combiningAcute = "\u0301"
        val zalgo = "A" + combiningAcute.repeat(79)
        val cappedStripUnsafe = "A" + combiningAcute.repeat(4)
        // displayName applies NFKC first, folding A+acute to Á before capping.
        val cappedDisplayName = "\u00C1" + combiningAcute.repeat(4)

        assertEquals(cappedDisplayName, ProfileSanitizer.displayName(zalgo))
        assertEquals(cappedStripUnsafe, ProfileSanitizer.stripUnsafe(zalgo))

        // Enclosing marks (Me) are also combining marks; cap them the same way.
        val enclosingCircle = "\u20DD"
        val enclosingZalgo = "A" + enclosingCircle.repeat(79)
        val cappedEnclosing = "A" + enclosingCircle.repeat(4)
        assertEquals(cappedEnclosing, ProfileSanitizer.displayName(enclosingZalgo))
        assertEquals(cappedEnclosing, ProfileSanitizer.stripUnsafe(enclosingZalgo))

        // ZWJ/ZWNJ are preserved for emoji and script shaping, but they are not
        // visual base characters and must not reset the mark budget.
        val joinerBypass = "A" + combiningAcute.repeat(4) + "\u200D" + combiningAcute.repeat(4)
        assertEquals(cappedStripUnsafe + "\u200D", ProfileSanitizer.stripUnsafe(joinerBypass))
    }

    @Test
    fun stripUnsafeRemovesSupplementaryPlaneFormatCharacters() {
        val tagSmallA = String(Character.toChars(0xE0061))
        val variationSelector = String(Character.toChars(0xE0101))

        assertEquals("Alice", ProfileSanitizer.displayName("A${tagSmallA}li${variationSelector}ce"))
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
    fun imageUrlsRejectEmbeddedCredentials() {
        assertNull(ProfileSanitizer.imageUrl("https://user:pass@example.com/avatar.png"))
        assertNull(ProfileSanitizer.imageUrl("https://user@example.com/avatar.png"))
        // The same host without userinfo still passes.
        assertEquals("https://example.com/avatar.png", ProfileSanitizer.imageUrl("https://example.com/avatar.png"))
    }

    @Test
    fun imageUrlsRejectExplicitNonStandardPorts() {
        assertEquals("https://example.com/avatar.png", ProfileSanitizer.imageUrl("https://example.com/avatar.png"))
        assertEquals("https://example.com:443/avatar.png", ProfileSanitizer.imageUrl("https://example.com:443/avatar.png"))
        assertNull(ProfileSanitizer.imageUrl("https://example.com:9200/avatar.png"))
    }

    @Test
    fun messageBodyPreservesNormalNewlinesButClampsBlankRuns() {
        assertEquals("one\n\ntwo", ProfileSanitizer.messageBody(" one\n\n\n\ntwo "))
    }
}
