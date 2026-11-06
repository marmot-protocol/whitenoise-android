package dev.ipf.whitenoise.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDictationMicrophoneLeaseTest {
    private var available = true
    private var releases = 0
    private var restores = 0
    private val lease =
        ConversationDictationMicrophoneLease(
            tryAcquire = { available },
            releaseMicrophone = { releases++ },
            afterCapture = { restores++ },
            diagnostic = {},
        )

    /** A busy microphone is not held and has no playback to restore. */
    @Test
    fun rejectedAcquisitionHoldsNothing() {
        available = false

        assertFalse(lease.acquire(1L))
        lease.finish(1L)

        assertFalse(lease.held)
        assertNull(lease.captureSessionId)
        assertEquals(0, releases)
        assertEquals(0, restores)
    }

    /** A replaced session's closing recorder still blocks the next session from reusing its lease. */
    @Test
    fun heldLeaseBelongsOnlyToItsCaptureSession() {
        assertTrue(lease.acquire(1L))

        assertFalse(lease.heldByOtherSession(1L))
        assertTrue(lease.heldByOtherSession(2L))
        lease.finish(2L)

        assertTrue(lease.held)
        assertEquals(0, releases)
    }

    /** Duplicate closure callbacks release the microphone and restore playback exactly once. */
    @Test
    fun finishReleasesAndRestoresExactlyOnce() {
        assertTrue(lease.acquire(1L))

        lease.finish(1L)
        lease.finish(1L)

        assertFalse(lease.held)
        assertFalse(lease.heldByOtherSession(2L))
        assertEquals(1, releases)
        assertEquals(1, restores)
    }

    /** Platform release failures cannot leave the lease held or skip playback restoration. */
    @Test
    fun releaseFailureStillEndsTheLease() {
        val failing =
            ConversationDictationMicrophoneLease(
                tryAcquire = { true },
                releaseMicrophone = { error("release failed") },
                afterCapture = { restores++ },
                diagnostic = {},
            )
        assertTrue(failing.acquire(1L))

        failing.finish(1L)

        assertFalse(failing.held)
        assertEquals(1, restores)
    }
}
