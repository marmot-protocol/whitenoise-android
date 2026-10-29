package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.media.AnimationAdmissionBusyException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A bounded group wait cannot manufacture admission or reset its budget after startup preemption. */
class AnimationAdmissionDeadlineTest {
    /** While a worker is held, a bounded decode times out as busy without ever opening a second worker. */
    @Test
    fun heldWorkerExpiresWithoutOpeningAnotherWorker() {
        val admission = AnimationAdmission()
        val held = checkNotNull(admission.acquire(true, 100))
        var opens = 0
        try {
            assertThrows(AnimationAdmissionBusyException::class.java) {
                admission.decode(true, true, 20) { lease ->
                    opens++
                    lease.release()
                    "unexpected"
                }
            }
            assertEquals(0, opens)
            assertNull(admission.acquire(true, 100))
        } finally {
            held.release()
        }
        checkNotNull(admission.acquire(true, 100)).release()
    }

    /** A startup preempted after its budget elapsed throws busy instead of retrying, so the budget never resets. */
    @Test
    fun startupPreemptionDoesNotResetExpiredBudget() {
        val admission = AnimationAdmission()
        var opens = 0
        assertThrows(AnimationAdmissionBusyException::class.java) {
            admission.decode<String>(true, true, 200) { lease ->
                opens++
                lease.retire()
                lease.release()
                Thread.sleep(250)
                null
            }
        }
        assertEquals(1, opens)
        checkNotNull(admission.acquire(true, 100)).release()
    }

    /** A release landing after the busy exception but before the owner waits is still seen via the generation. */
    @Test
    fun releaseBeforeLifecycleObservationCannotBeLost() =
        runBlocking {
            val admission = AnimationAdmission()
            val held = checkNotNull(admission.acquire(true, 100))
            val busy = assertThrows(AnimationAdmissionBusyException::class.java) { admission.awaitDecorative(20) }
            held.release()
            withTimeout(1000) { busy.awaitAvailability() }
            val next = admission.awaitDecorative(100)
            assertNotNull(next)
            next.release()
        }

    /**
     * A release arriving while an expired wait reacquires the lock still gives the ready FIFO head its
     * acquisition instead of busy.
     */
    @Test
    fun releaseBeforeExpiredWaitReacquiresLockCannotBeLost() =
        runBlocking {
            val admission = AnimationAdmission()
            val held = checkNotNull(admission.acquire(true, 100))
            val failure = AtomicReference<AnimationAdmissionBusyException?>()
            val waiting =
                Thread {
                    try {
                        admission.awaitDecorative(100).release()
                    } catch (busy: AnimationAdmissionBusyException) {
                        failure.set(busy)
                    }
                }
            try {
                waiting.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (waiting.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.TIMED_WAITING, waiting.state)
                val lock =
                    AnimationAdmission::class.java
                        .getDeclaredField("lock")
                        .apply { isAccessible = true }
                        .get(admission)
                synchronized(lock) {
                    Thread.sleep(150)
                    held.release()
                }
                waiting.join(2000)
                assertNull(failure.get()) // Ready FIFO head gets its immediate acquisition before expiry.
                admission.awaitDecorative(100).release()
            } finally {
                held.release()
                waiting.interrupt()
                waiting.join(2000)
            }
        }

    /** A free worker may be acquired with a zero wait, but an expired decode budget still forbids every OPEN. */
    @Test
    fun alreadyFreeExpiredAttemptDoesNotParkWithoutAWorker() =
        runBlocking {
            val admission = AnimationAdmission()
            admission.awaitDecorative(0).release() // Free acquisition is allowed; this starts no native work.
            var opens = 0
            assertThrows(AnimationAdmissionBusyException::class.java) {
                admission.decode(true, true, 0) {
                    opens++
                    it.release()
                    "unexpected"
                }
            }
            assertEquals(0, opens) // Expired total decode budget still forbids every new OPEN.
            admission.awaitDecorative(100).release()
        }
}
