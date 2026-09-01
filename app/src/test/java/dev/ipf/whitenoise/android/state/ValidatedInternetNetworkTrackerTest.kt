package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedInternetNetworkTrackerTest {
    /** Advances the probe fence only when validated-internet identity changes. */
    @Test
    fun usableRecoveryWakesOnlyAfterBothRouteAndValidatedUpstreamRecover() {
        val tracker = UsableValidatedInternetRecoveryTracker()
        tracker.seed(
            hasActiveDefaultNetwork = false,
            hasValidatedPhysicalNetwork = false,
        )

        val routeOnly =
            tracker.update(
                hasActiveDefaultNetwork = true,
                hasValidatedPhysicalNetwork = false,
            )
        assertFalse(routeOnly.hasUsableInternet)
        assertFalse(routeOnly.restored)

        val validated =
            tracker.update(
                hasActiveDefaultNetwork = true,
                hasValidatedPhysicalNetwork = true,
            )
        assertTrue(validated.hasUsableInternet)
        assertTrue(validated.restored)

        val duplicateCallback =
            tracker.update(
                hasActiveDefaultNetwork = true,
                hasValidatedPhysicalNetwork = true,
            )
        assertTrue(duplicateCallback.hasUsableInternet)
        assertFalse(duplicateCallback.restored)
    }

    @Test
    fun connectivitySignalGenerationChangesOnlyAtValidatedInternetEdges() {
        val owner = ConnectivitySignalOwner()

        owner.update(hasValidatedInternet = false)
        assertEquals(0L, owner.captureNetworkGeneration())
        owner.update(hasValidatedInternet = true)
        assertEquals(1L, owner.captureNetworkGeneration())
        owner.update(relaysConnected = false)
        assertEquals(1L, owner.captureNetworkGeneration())
        owner.update(hasValidatedInternet = false, relaysConnected = true)
        assertEquals(2L, owner.captureNetworkGeneration())
        assertFalse(owner.signals.value.relaysConnected)
    }

    /** Invalidates an in-flight probe when Android reports a replacement default network. */
    @Test
    fun defaultNetworkIdentityChangeAdvancesTheStaleCompletionFence() {
        val owner = ConnectivitySignalOwner()

        owner.update(hasValidatedInternet = true)
        owner.noteNetworkIdentityChange()

        assertEquals(2L, owner.captureNetworkGeneration())
        assertTrue(owner.signals.value.hasValidatedInternet)
    }

    @Test
    fun lateLossForOldDefaultCannotClearItsReplacement() {
        val tracker = ActiveDefaultNetworkTracker()
        tracker.seed(networkHandle = 1L)
        assertTrue(tracker.available(networkHandle = 2L).identityChanged)

        assertEquals(null, tracker.lost(networkHandle = 1L, replacementNetworkHandle = 2L))
        assertTrue(tracker.isCurrent(networkHandle = 2L))
    }

    @Test
    fun currentDefaultLossUsesAnAlreadyAvailableReplacement() {
        val tracker = ActiveDefaultNetworkTracker()
        tracker.seed(networkHandle = 1L)

        val replacement = requireNotNull(tracker.lost(networkHandle = 1L, replacementNetworkHandle = 2L))
        assertTrue(replacement.hasActiveNetwork)
        assertTrue(replacement.identityChanged)
        assertTrue(tracker.isCurrent(networkHandle = 2L))
        assertFalse(requireNotNull(tracker.lost(networkHandle = 2L, replacementNetworkHandle = null)).hasActiveNetwork)
    }

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
