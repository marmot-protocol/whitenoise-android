package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.SharedLocation
import dev.ipf.whitenoise.android.ui.conversation.share.buildVCard
import dev.ipf.whitenoise.android.ui.conversation.share.formatContactShareText
import dev.ipf.whitenoise.android.ui.conversation.share.formatLocationShareText
import dev.ipf.whitenoise.android.ui.conversation.share.formatUserShareText
import dev.ipf.whitenoise.android.ui.conversation.share.isBareLocationShare
import dev.ipf.whitenoise.android.ui.conversation.share.isContactShareCaption
import dev.ipf.whitenoise.android.ui.conversation.share.locationGrantAllowsSharing
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedContactFromText
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedLocationFromText
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedUserFromText
import dev.ipf.whitenoise.android.ui.conversation.share.selectLocationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pins the share text fallbacks and the location permission/provider
 * decisions. The fallback shapes are what peers actually receive until
 * structured contact/location message kinds exist, so they stay exact.
 */
class ShareFormattingTest {
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"

    @Test
    fun contactWithAllFieldsFormatsAsThreeLines() {
        assertEquals(
            "Ada Lovelace\n+1 555 0100\nada@example.org",
            formatContactShareText(
                SharedContact(name = "Ada Lovelace", phone = "+1 555 0100", email = "ada@example.org"),
            ),
        )
    }

    @Test
    fun missingContactFieldsAreSkippedWithoutBlankLines() {
        assertEquals(
            "Ada Lovelace\nada@example.org",
            formatContactShareText(
                SharedContact(name = "Ada Lovelace", phone = null, email = "ada@example.org"),
            ),
        )
        assertEquals(
            "+1 555 0100",
            formatContactShareText(
                SharedContact(name = null, phone = "+1 555 0100", email = null),
            ),
        )
    }

    @Test
    fun locationFallbackUsesTheMapsUrlShape() {
        assertEquals(
            "Location: https://maps.google.com/maps?q=52.520008,13.404954",
            formatLocationShareText(
                SharedLocation(latitude = 52.520008, longitude = 13.404954, accuracyMeters = 12),
            ),
        )
    }

    @Test
    fun coordinatesStayDotDecimalUnderCommaLocales() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                "Location: https://maps.google.com/maps?q=-33.868820,151.209290",
                formatLocationShareText(
                    SharedLocation(latitude = -33.86882, longitude = 151.20929, accuracyMeters = null),
                ),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun locationRoundTripsThroughItsOwnFallback() {
        val original = SharedLocation(latitude = 11.871263, longitude = 8.534887, accuracyMeters = null)
        val parsed = parseSharedLocationFromText(formatLocationShareText(original))
        assertEquals(original.latitude, parsed?.latitude)
        assertEquals(original.longitude, parsed?.longitude)
    }

    @Test
    fun locationParsesLegacyAndEncodedForms() {
        assertEquals(
            11.871263,
            parseSharedLocationFromText("Location: https://maps.google.com/?q=11.871263%2C8.534887")?.latitude,
        )
        assertNull(parseSharedLocationFromText("just a normal message, no coordinates"))
        assertNull(parseSharedLocationFromText("https://maps.google.com/maps?q=999,999"))
    }

    /** A maps link inside prose still draws the card, but the prose stays visible. */
    @Test
    fun locationInsideProseParsesWithoutOwningTheBody() {
        val body = "Meet me here: https://maps.google.com/maps?q=11.871263,8.534887 at noon"
        assertEquals(11.871263, parseSharedLocationFromText(body)?.latitude)
        assertEquals(8.534887, parseSharedLocationFromText(body)?.longitude)
        assertFalse(isBareLocationShare(body))
        assertTrue(isBareLocationShare(" Location: https://maps.google.com/maps?q=11.871263,8.534887\n"))
    }

    @Test
    fun outOfRangeLinkDoesNotHideALaterValidLink() {
        val body = "https://maps.google.com/maps?q=999,999 or https://maps.google.com/maps?q=11.871263,8.534887"
        assertEquals(11.871263, parseSharedLocationFromText(body)?.latitude)
    }

    /** Sender-controlled whitespace must not make ordinary conversation text quadratic to scan. */
    @Test(timeout = 5_000)
    fun longWhitespaceWithoutAMapsLinkRemainsOrdinaryText() {
        val body = "Before" + " \n".repeat(50_000) + "after"
        assertNull(parseSharedLocationFromText(body))
        assertFalse(isBareLocationShare(body))
    }

    /** Skipping a long whitespace run still finds the link without consuming its caption. */
    @Test(timeout = 5_000)
    fun longWhitespaceBeforeAMapsLinkPreservesProse() {
        val body = "Meet here" + " \n".repeat(50_000) + "https://maps.google.com/maps?q=-33.868820,151.209290"
        assertEquals(-33.86882, parseSharedLocationFromText(body)?.latitude)
        assertEquals(151.20929, parseSharedLocationFromText(body)?.longitude)
        assertFalse(isBareLocationShare(body))
    }

    @Test
    fun bareLocationCheckRetainsLegacyCaseAndEncodedCoordinates() {
        val body = " \nLOCATION: HTTPS://MAPS.GOOGLE.COM/?Q=-33.868820%2c151.209290\t"
        assertEquals(-33.86882, parseSharedLocationFromText(body)?.latitude)
        assertTrue(isBareLocationShare(body))
        assertFalse(isBareLocationShare("Meet here: $body"))
    }

    @Test
    fun contactParsesBackFromItsCaption() {
        val contact = SharedContact(name = "Ada Lovelace", phone = "+1 555 0100", email = "ada@example.org")
        val parsed = parseSharedContactFromText(formatContactShareText(contact))
        assertEquals("Ada Lovelace", parsed?.name)
        assertEquals("+1 555 0100", parsed?.phone)
        assertEquals("ada@example.org", parsed?.email)
        assertNull(parseSharedContactFromText("See the attached details"))
    }

    /** Sender prose that mentions a number stays a caption; only a bare number line is a phone. */
    @Test
    fun captionProseWithANumberIsNotContactText() {
        assertNull(parseSharedContactFromText("Please call 555-0100 tomorrow"))
        assertNull(parseSharedContactFromText("Ada\nRoom 4012345, ask at the desk"))
        assertEquals("+1 (555) 010-0100", parseSharedContactFromText("Ada\n+1 (555) 010-0100")?.phone)
    }

    /** Only the exact generated caption may be replaced by the card; extra sender words keep it visible. */
    @Test
    fun onlyTheGeneratedCaptionCountsAsContactText() {
        val contact = SharedContact(name = "Ada Example", phone = "+1 555 0100", email = null)
        assertTrue(isContactShareCaption(" Ada Example \n\n+1 555 0100\n", contact))
        assertFalse(isContactShareCaption("Call Ada\n+1 555 0100", contact))
        assertFalse(isContactShareCaption("Ada Example\n+1 555 0100\nAfter six", contact))

        // A picked contact without a name carries its number as the vCard name but not in the caption.
        val unnamed = SharedContact(name = "+1 555 0100", phone = "+1 555 0100", email = null)
        assertTrue(isContactShareCaption("+1 555 0100", unnamed))
    }

    private val sampleNpub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    /** Pins the sender's canonical wire format to one identity reference and no inferred label. */
    @Test
    fun userShareSendsOnlyAnUnambiguousNostrReference() {
        assertEquals(
            "nostr:$sampleNpub",
            formatUserShareText(sampleNpub),
        )
    }

    /** Proves the canonical send format retains identity while introducing no title ambiguity. */
    @Test
    fun userShareRoundTripsNpubWithoutAnAmbiguousNameLine() {
        val parsed = parseSharedUserFromText(formatUserShareText(sampleNpub))
        assertNull(parsed?.name)
        assertEquals(sampleNpub, parsed?.npub)
    }

    @Test
    fun bareNpubParsesAsAUserShare() {
        assertEquals(sampleNpub, parseSharedUserFromText(sampleNpub)?.npub)
        assertNull(parseSharedUserFromText(sampleNpub)?.name)
    }

    /** Rejects inline and multiline prose regardless of whether it precedes or follows the npub. */
    @Test
    fun proseMentioningAnNpubIsNotHijackedIntoAUserCard() {
        // A longer message that merely contains an npub stays plain text.
        assertNull(parseSharedUserFromText("hey check out nostr:$sampleNpub they post great stuff"))
        assertNull(parseSharedUserFromText("Ada Lovelace\nnostr:$sampleNpub"))
        assertNull(parseSharedUserFromText("nostr:$sampleNpub\nAda Lovelace"))
        assertNull(parseSharedUserFromText("line one\nline two\nnostr:$sampleNpub"))
        assertNull(parseSharedUserFromText("no reference here at all"))
        assertNull(parseSharedUserFromText("nostr:npub1notavalidprofile"))
    }

    @Test
    fun vcardCarriesNameAndFieldsForPortability() {
        val vcard = buildVCard(SharedContact(name = "Ada Lovelace", phone = "+15550100", email = "ada@example.org"))
        assertTrue(vcard.startsWith("BEGIN:VCARD"))
        assertTrue(vcard.contains("N:Ada Lovelace;;;;"))
        assertTrue(vcard.contains("FN:Ada Lovelace"))
        assertTrue(vcard.contains("TEL;TYPE=CELL:+15550100"))
        assertTrue(vcard.contains("EMAIL:ada@example.org"))
        assertTrue(vcard.trimEnd().endsWith("END:VCARD"))
    }

    @Test
    fun anyLocationGrantAllowsSharing() {
        assertTrue(locationGrantAllowsSharing(mapOf(fine to true, coarse to false)))
        assertTrue(locationGrantAllowsSharing(mapOf(fine to false, coarse to true)))
    }

    @Test
    fun deniedGrantsBlockSharingSafely() {
        assertFalse(locationGrantAllowsSharing(mapOf(fine to false, coarse to false)))
        assertFalse(locationGrantAllowsSharing(emptyMap()))
    }

    @Test
    fun coarseOnlyGrantNeverSelectsGps() {
        assertEquals(
            "network",
            selectLocationProvider(enabledProviders = listOf("gps", "network"), hasFineGrant = false),
        )
    }

    @Test
    fun fusedProviderIsPreferredWhenEnabled() {
        assertEquals(
            "fused",
            selectLocationProvider(enabledProviders = listOf("gps", "fused", "network"), hasFineGrant = true),
        )
    }

    @Test
    fun noEnabledProviderYieldsNull() {
        assertNull(selectLocationProvider(enabledProviders = emptyList(), hasFineGrant = true))
    }
}
