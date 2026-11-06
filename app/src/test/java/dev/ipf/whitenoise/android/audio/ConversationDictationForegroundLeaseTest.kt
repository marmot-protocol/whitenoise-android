package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDictationForegroundLeaseTest {
    private var stops = 0
    private val lease = ConversationDictationForegroundLease(stopService = { stops++ }, diagnostic = {})

    /** Capture readiness needs both the enqueue acceptance and the promotion, in either order. */
    @Test
    fun readinessRequiresAcceptedStartAndPromotionInEitherOrder() {
        assertTrue(lease.begin())
        assertFalse(lease.notePromotion())
        assertTrue(lease.acceptStart())
        lease.release()

        assertTrue(lease.begin())
        assertFalse(lease.acceptStart())
        assertTrue(lease.notePromotion())
        lease.markReady()
        assertTrue(lease.ready)
    }

    /** A second begin keeps the running lease and its token generation. */
    @Test
    fun beginWhileActiveKeepsTheCurrentGeneration() {
        assertTrue(lease.begin())
        val generation = lease.generation

        assertFalse(lease.begin())

        assertEquals(generation, lease.generation)
        lease.release()
        assertTrue(lease.begin())
        assertEquals(generation + 1L, lease.generation)
    }

    /** Release stops the service once, cancels its promotion timeout and forgets a stale handshake. */
    @Test
    fun releaseStopsTheServiceOnceAndResetsTheHandshake() {
        var timeoutCancels = 0
        assertTrue(lease.begin())
        lease.armPromotionTimeout { timeoutCancels++ }
        lease.notePromotion()

        lease.release()
        lease.release()

        assertEquals(1, stops)
        assertEquals(1, timeoutCancels)
        assertFalse(lease.active)
        assertTrue(lease.begin())
        assertFalse(lease.acceptStart())
    }

    /** A service Android already destroyed is not stopped again by a later release. */
    @Test
    fun destroyedServiceIsNotStoppedAgain() {
        assertTrue(lease.begin())
        lease.markReady()

        lease.onServiceDestroyed()
        lease.release()

        assertFalse(lease.ready)
        assertEquals(0, stops)
    }

    /** A deferred release keeps the service token and ignores closures from other capture generations. */
    @Test
    fun deferredReleaseWaitsForItsOwnCaptureClosure() {
        assertTrue(lease.begin())
        lease.deferReleaseUntilCaptureClosed(captureGeneration = 4L, token = "token")
        assertTrue(lease.releaseDeferred)
        assertEquals("token", lease.deferredReleaseToken)

        lease.releaseAfterCaptureClosed(3L)
        assertTrue(lease.active)
        assertEquals(0, stops)

        lease.releaseAfterCaptureClosed(4L)
        lease.releaseAfterCaptureClosed(4L)

        assertFalse(lease.active)
        assertFalse(lease.releaseDeferred)
        assertNull(lease.deferredReleaseToken)
        assertEquals(1, stops)
    }

    /** A stop failure still ends the logical lease. */
    @Test
    fun stopFailureStillEndsTheLease() {
        val failing = ConversationDictationForegroundLease(stopService = { error("stop failed") }, diagnostic = {})
        assertTrue(failing.begin())

        failing.release()

        assertFalse(failing.active)
        assertTrue(failing.begin())
    }
}
