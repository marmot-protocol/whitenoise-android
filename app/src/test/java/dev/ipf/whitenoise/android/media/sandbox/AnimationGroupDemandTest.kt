package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.media.AnimationAdmissionBusyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Real admission tickets survive parked waits and respect the physical retirement boundary. */
class AnimationGroupDemandTest {
    /** Pending group tickets block inline acquisition after a release, and closing the head hands admission on. */
    @Test
    fun parkedFifoGroupWinsAnInlineRaceAndCancellationWakesItsSuccessor() {
        val admission = AnimationAdmission()
        val viewer = checkNotNull(admission.acquire(false, 100))
        val first = admission.groupDemand()
        val second = admission.groupDemand()
        try {
            assertThrows(AnimationAdmissionBusyException::class.java) {
                admission.decode(true, true, 0, first) { error("expired budget cannot OPEN") }
            }
            viewer.release()
            assertNull(admission.acquire(true, 100))
            first.close()
            assertNull(admission.acquire(true, 100))
            val result =
                admission.decode(true, true, 100, second) { lease ->
                    lease.release()
                    "second"
                }
            assertEquals("second", result)
        } finally {
            first.close()
            second.close()
            viewer.release()
        }
        checkNotNull(admission.acquire(true, 100)).release()
    }

    /** Group demand retires an inline lease once, but only releasing that lease frees capacity, never a ticket. */
    @Test
    fun groupDemandRetiresInlineExactlyOnceButCannotManufactureDeath() {
        val admission = AnimationAdmission()
        val inline = checkNotNull(admission.acquire(true, 100))
        var retirements = 0
        inline.onRetire { retirements++ }
        val first = admission.groupDemand()
        val second = admission.groupDemand()
        try {
            assertEquals(1, retirements)
            assertThrows(AnimationAdmissionBusyException::class.java) {
                admission.decode(true, true, 0, first) { error("live worker cannot OPEN twice") }
            }
            assertNull(admission.acquire(true, 100))
            inline.release() // Only this models confirmed death, never ticket/status/timeout.
            assertEquals(
                "first",
                admission.decode(true, true, 100, first) {
                    it.release()
                    "first"
                },
            )
            assertNull(admission.acquire(true, 100))
        } finally {
            first.close()
            second.close()
            inline.release()
        }
    }

    /** A group in fault backoff yields to a healthy group, then rejoins the FIFO at the tail when backoff ends. */
    @Test
    fun faultBackoffLetsHealthyGroupProceedAndRequeuesAtTheTail() {
        val admission = AnimationAdmission()
        val first = admission.groupDemand()
        val second = admission.groupDemand()
        try {
            first.beginBackoff()
            assertEquals(
                "second",
                admission.decode(true, true, 100, second) {
                    it.release()
                    "second"
                },
            )
            first.endBackoff()
            assertNull(admission.acquire(true, 100))
            assertEquals(
                "first",
                admission.decode(true, true, 100, first) {
                    it.release()
                    "first"
                },
            )
        } finally {
            first.close()
            second.close()
        }
    }
}
