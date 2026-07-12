package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.SharedLocation
import dev.ipf.whitenoise.android.ui.conversation.share.formatContactShareText
import dev.ipf.whitenoise.android.ui.conversation.share.formatLocationShareText
import dev.ipf.whitenoise.android.ui.conversation.share.locationGrantAllowsSharing
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
            "Location: https://maps.google.com/?q=52.520008,13.404954",
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
                "Location: https://maps.google.com/?q=-33.868820,151.209290",
                formatLocationShareText(
                    SharedLocation(latitude = -33.86882, longitude = 151.20929, accuracyMeters = null),
                ),
            )
        } finally {
            Locale.setDefault(previous)
        }
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
