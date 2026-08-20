package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.fuzz.FuzzSyntheticCorpusReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileLinkTest {
    @Test
    fun replaysSyntheticFuzzCorpus() {
        FuzzSyntheticCorpusReplay.replaySuite(FuzzSyntheticCorpusReplay.Suite.ProfileLink)
    }

    // A bech32 npub is `npub1` + 58 chars from the bech32 alphabet.
    private val sampleNpub = "npub1" + "a".repeat(58)

    @Test
    fun buildsMarmotProfileDeepLinks() {
        val link = ProfileLink(sampleNpub)

        // Generates the cross-client marmot:// scheme (issue #1018); the QR
        // variant carries the ?from=qr provenance hint.
        assertEquals("marmot://profile/$sampleNpub", link.uri)
        assertEquals("marmot://profile/$sampleNpub?from=qr", link.qrUri)
    }

    @Test
    fun parsesMarmotWhiteNoiseNostrAndBareNpubPayloads() {
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("marmot://profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("marmot://profile/$sampleNpub?from=qr"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("marmot://$sampleNpub"))
        // whitenoise:// stays recognized for inbound/legacy links.
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise://profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise://$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise-staging://profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise-dev://profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("https://whitenoise.chat/profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("https://www.whitenoise.chat/$sampleNpub?from=share"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("https://marmot.app/profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("nostr:$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("  $sampleNpub  "))
    }

    @Test
    fun generatedLinkAlwaysRoundTripsThroughParse() {
        // The generator emits from BuildConfig.WHITENOISE_DEEP_LINK_SCHEME while
        // parse() recognizes the PROFILE_SCHEMES set. #847: the set now always
        // includes the BuildConfig scheme, so a self-generated link can never
        // fail to be recognized even if a future flavor adds a new scheme.
        val generated = ProfileLink(sampleNpub).uri
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse(generated))
    }

    @Test
    fun parsesOpaqueSchemeSpecificPartLinks() {
        // Opaque form `scheme:profile/npub…` (no `//`): host/path are null, npub
        // lives in schemeSpecificPart. Externally-authored, but should resolve (#847).
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise:profile/$sampleNpub"))
        assertEquals(ProfileLink(sampleNpub), ProfileLink.parse("whitenoise:$sampleNpub"))
        // Opaque payload that isn't an npub still rejects.
        assertNull(ProfileLink.parse("whitenoise:profile/not-a-profile"))
        assertNull(ProfileLink.parse("whitenoise:profile/"))
    }

    @Test
    fun rejectsNonProfilePayloads() {
        assertNull(ProfileLink.parse("https://example.com/$sampleNpub"))
        assertNull(ProfileLink.parse("https://whitenoise.chat/not-profile/$sampleNpub"))
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

    @Test
    fun constructorRejectsMalformedNpubs() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileLink("npub1abc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileLink("npub1" + "b".repeat(58))
        }
    }
}
