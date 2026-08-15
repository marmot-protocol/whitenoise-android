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
    fun staleValidatedPhysicalNetworkCannotStandInForAnActiveDefaultNetwork() {
        assertFalse(
            hasUsableValidatedInternet(
                hasActiveDefaultNetwork = false,
                hasValidatedPhysicalNetwork = true,
            ),
        )
        assertFalse(
            hasUsableValidatedInternet(
                hasActiveDefaultNetwork = true,
                hasValidatedPhysicalNetwork = false,
            ),
        )
        assertTrue(
            hasUsableValidatedInternet(
                hasActiveDefaultNetwork = true,
                hasValidatedPhysicalNetwork = true,
            ),
        )
    }

    @Test
    fun initialSnapshotIsAvailableWithoutWaitingForACallback() {
        val tracker = ValidatedInternetNetworkTracker()

        assertTrue(
            tracker.seedAtomically {
                mapOf(
                    1L to true,
                    2L to false,
                )
            },
        )
        assertTrue(tracker.hasValidatedInternet())
    }

    @Test
    fun callbackAfterInitialSnapshotWinsTheAtomicHandoff() {
        val tracker = ValidatedInternetNetworkTracker()
        tracker.seedAtomically { mapOf(1L to true) }

        assertFalse(tracker.remove(networkHandle = 1L))
        assertFalse(tracker.hasValidatedInternet())
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
