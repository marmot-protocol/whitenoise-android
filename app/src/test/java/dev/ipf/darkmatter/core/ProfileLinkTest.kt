package dev.ipf.darkmatter.core

import dev.ipf.darkmatter.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileLinkTest {
    // A bech32 npub is `npub1` + 58 chars from the bech32 alphabet.
    private val sampleNpub = "npub1" + "a".repeat(58)

    @Test
    fun buildsWhiteNoiseProfileDeepLinks() {
        val link = ProfileLink(sampleNpub)

        assertEquals("${BuildConfig.WHITENOISE_DEEP_LINK_SCHEME}://profile/$sampleNpub", link.uri)
    }

    @Test
    fun buildsHttpsProfileLinksWhenConfigured() {
        assertEquals(
            "https://www.whitenoise.chat/profile/$sampleNpub",
            ProfileLink.buildUri(
                npub = sampleNpub,
                appLinkBaseUrl = "https://www.whitenoise.chat/profile",
                customScheme = "whitenoise",
            ),
        )
    }

    @Test
    fun parsesWhiteNoiseNostrAndBareNpubPayloads() {
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise://profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise://$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise-staging://profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise-dev://profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("nostr:$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("  $sampleNpub  "))
    }

    @Test
    fun parsesVerifiedHttpsProfileLinks() {
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("https://www.whitenoise.chat/profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("https://whitenoise.chat/profile/$sampleNpub"))
    }

    @Test
    fun rejectsNonCanonicalHttpsProfileLinks() {
        assertNull(ProfileLink.parse("https://www.whitenoise.chat:444/profile/$sampleNpub"))
        assertNull(ProfileLink.parse("https://user@www.whitenoise.chat/profile/$sampleNpub"))
        assertNull(ProfileLink.parse("https://www.whitenoise.chat/PROFILE/$sampleNpub"))
        assertNull(ProfileLink.parse("https://www.whitenoise.chat/profile/$sampleNpub/extra"))
        assertNull(ProfileLink.parse("https://www.whitenoise.chat/profile/$sampleNpub?ref=qr"))
        assertNull(ProfileLink.parse("https://www.whitenoise.chat/profile/$sampleNpub#fragment"))
    }

    @Test
    fun parsesLegacyDarkMatterProfileLinks() {
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("darkmatter://profile/$sampleNpub"))
    }

    @Test
    fun rejectsNonProfilePayloads() {
        assertNull(ProfileLink.parse("https://example.com/$sampleNpub"))
        assertNull(ProfileLink.parse("http://www.whitenoise.chat/profile/$sampleNpub"))
        assertNull(ProfileLink.parse("https://www.whitenoise.chat/not-profile/$sampleNpub"))
        assertNull(ProfileLink.parse("whitenoise://profile/not-a-profile"))
        assertNull(ProfileLink.parse(""))
    }

    @Test
    fun rejectsMalformedNpubInputs() {
        // Too short — bech32 npub is exactly 63 characters.
        assertNull(ProfileLink.parse("npub"))
        assertNull(ProfileLink.parse("npub1"))
        assertNull(ProfileLink.parse("npub1abc"))
        // Too long.
        assertNull(ProfileLink.parse("npub1" + "a".repeat(100)))
        // Wrong prefix.
        assertNull(ProfileLink.parse("npub2" + "a".repeat(58)))
        // Out-of-alphabet body characters (bech32 omits b, i, o, 1).
        assertNull(ProfileLink.parse("npub1" + "b".repeat(58)))
        assertNull(ProfileLink.parse("npub1" + "i".repeat(58)))
        // Garbage with the right prefix but wrong shape doesn't smuggle through.
        assertNull(ProfileLink.parse("nostr:npub1garbage"))
        assertNull(ProfileLink.parse("whitenoise://profile/npub1garbage"))
    }
}
