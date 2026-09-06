package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the bounded request handoff used by the real foreground-service owner. */
class CapabilityFallbackServiceRequestsTest {
    /** A request received during bootstrap replaces stale work and joins the same supervisor attempt. */
    @Test
    fun requestDuringBootstrapJoinsTheSuccessfulAttempt() {
        val requests = CapabilityFallbackServiceRequests()

        assertTrue(requests.register(7L).isEmpty())
        assertTrue(requests.register(8L).isEmpty())

        assertEquals(setOf(8L), requests.onRuntimeStarted())
        assertTrue(requests.onRuntimeStarted().isEmpty())
    }

    /** A live supervisor boundary can acknowledge a newly queued exact generation immediately. */
    @Test
    fun requestAfterRuntimeStartIsAcknowledgedImmediately() {
        val requests = CapabilityFallbackServiceRequests()
        requests.onRuntimeStarted()

        assertEquals(setOf(8L), requests.register(8L))
    }

    /** Latest-wins tracking retains no acknowledged history after a replacement and teardown. */
    @Test
    fun newerRequestEvictsAcknowledgedHistoryAndOwnsDestroyInvalidation() {
        val requests = CapabilityFallbackServiceRequests()
        requests.register(9L)
        requests.onRuntimeStarted()

        assertEquals(setOf(10L), requests.register(10L))
        assertEquals(setOf(10L), requests.onRuntimeUnavailable())
        assertTrue(requests.onRuntimeUnavailable().isEmpty())
    }

    /** A delayed rejection cannot invalidate a newer generation attached to the service. */
    @Test
    fun rejectionInvalidatesOnlyItsMatchingCurrentGeneration() {
        val requests = CapabilityFallbackServiceRequests()
        requests.register(11L)
        requests.register(12L)

        assertTrue(requests.reject(11L).isEmpty())
        assertEquals(setOf(12L), requests.onRuntimeStarted())
    }

    /** Missing and reserved-zero generations never acquire service ownership. */
    @Test
    fun invalidGenerationsNeverEnterServiceOwnership() {
        val requests = CapabilityFallbackServiceRequests()

        assertTrue(requests.register(null).isEmpty())
        assertTrue(requests.register(0L).isEmpty())
        assertTrue(requests.onRuntimeStarted().isEmpty())
        assertTrue(requests.onRuntimeUnavailable().isEmpty())
    }
}
