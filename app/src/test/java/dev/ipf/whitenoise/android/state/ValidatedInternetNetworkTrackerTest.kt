package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedInternetNetworkTrackerTest {
    @Test
    fun staleVpnCapabilitiesDoNotCountAsUsableInternet() {
        assertFalse(
            isValidatedNonVpnInternet(
                hasInternetCapability = true,
                hasValidatedCapability = true,
                hasNotVpnCapability = false,
            ),
        )
    }

    @Test
    fun physicalNetworkCountsOnlyAfterAndroidValidatesInternetAccess() {
        assertFalse(
            isValidatedNonVpnInternet(
                hasInternetCapability = true,
                hasValidatedCapability = false,
                hasNotVpnCapability = true,
            ),
        )
        assertTrue(
            isValidatedNonVpnInternet(
                hasInternetCapability = true,
                hasValidatedCapability = true,
                hasNotVpnCapability = true,
            ),
        )
    }

    @Test
    fun losingLastValidatedUpstreamClearsUsableInternet() {
        val tracker = ValidatedInternetNetworkTracker()
        tracker.update(
            networkHandle = 1L,
            available = true,
        )
        tracker.update(
            networkHandle = 2L,
            available = true,
        )

        assertTrue(tracker.remove(networkHandle = 1L))
        assertTrue(tracker.hasValidatedInternet())
        assertFalse(tracker.remove(networkHandle = 2L))
        assertFalse(tracker.hasValidatedInternet())
    }
}
