package dev.ipf.whitenoise.android.media.sandbox

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Admission remains tied to actual worker death across navigation, cancellation and viewer takeover. */
class AnimationAdmissionTest {
    /** A replacement group avatar waits through retirement instead of permanently treating busy as failure. */
    @org.junit.Test
    fun groupWaitsForConfirmedDeathWithoutTakingASecondLease() {
        val admission = AnimationAdmission()
        val previous = checkNotNull(admission.acquire(true, 100))
        val result = AtomicReference<AnimationAdmission.Lease?>()
        val replacement = Thread { result.set(admission.awaitDecorative()) }
        try {
            replacement.start()
            awaitBlocked(replacement)
            previous.retire()
            assertNull(result.get())
            assertNull(admission.acquire(true, 100))
            previous.release()
            replacement.join(2000)
            assertFalse(replacement.isAlive)
            checkNotNull(result.get())
            assertNull(admission.acquire(true, 100))
        } finally {
            replacement.interrupt()
            replacement.join(2000)
            previous.release()
            result.get()?.release()
        }
    }

    /** An off-screen group's interrupt cancels its wait without consuming future decoder admission. */
    @org.junit.Test
    fun cancelledGroupWaitNeverStartsAfterTheOldWorkerDies() {
        val admission = AnimationAdmission()
        val previous = checkNotNull(admission.acquire(true, 100))
        val cancelled = CountDownLatch(1)
        val result = AtomicReference<AnimationAdmission.Lease?>()
        val waiting =
            Thread {
                try {
                    result.set(admission.awaitDecorative())
                } catch (_: InterruptedException) {
                    cancelled.countDown()
                }
            }
        try {
            waiting.start()
            awaitBlocked(waiting)
            waiting.interrupt()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            waiting.join(2000)
            previous.release()
            assertNull(result.get())
            checkNotNull(admission.acquire(true, 100)).release()
        } finally {
            waiting.interrupt()
            waiting.join(2000)
            previous.release()
            result.get()?.release()
        }
    }

    /** A queued thumbnail cannot steal the retiring worker's slot from the explicit viewer. */
    @org.junit.Test
    fun interactiveViewerHasPriorityOverAWaitingGroup() {
        val admission = AnimationAdmission()
        val previous = checkNotNull(admission.acquire(true, 100))
        val retirement = CountDownLatch(1)
        previous.onRetire { retirement.countDown() }
        val groupResult = AtomicReference<AnimationAdmission.Lease?>()
        val viewerResult = AtomicReference<AnimationAdmission.Lease?>()
        val group = Thread { groupResult.set(admission.awaitDecorative()) }
        val viewer = Thread { viewerResult.set(admission.acquire(false, 2000)) }
        try {
            group.start()
            awaitBlocked(group)
            viewer.start()
            assertTrue(retirement.await(2, TimeUnit.SECONDS))
            previous.release()
            viewer.join(2000)
            val interactive = checkNotNull(viewerResult.get())
            assertNull(groupResult.get())
            interactive.release()
            group.join(2000)
            assertFalse(group.isAlive)
            checkNotNull(groupResult.get())
            assertNull(admission.acquire(true, 100))
        } finally {
            group.interrupt()
            viewer.interrupt()
            group.join(2000)
            viewer.join(2000)
            previous.release()
            viewerResult.get()?.release()
            groupResult.get()?.release()
        }
    }

    /** Waits for the real blocking operation, so a returned-null regression cannot pass by scheduling luck. */
    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.isAlive && thread.state != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue("group must wait without polling", thread.state == Thread.State.WAITING)
    }

    /** A retirement request, late callback or timed-out viewer must never manufacture another worker slot. */
    @org.junit.Test
    fun interactiveUseRetiresDecorativeWorkerButWaitsForItsDeath() {
        val admission = AnimationAdmission()
        val thumbnail = checkNotNull(admission.acquire(true, 100))
        check(admission.acquire(true, 100) == null)
        val retirement = CountDownLatch(1)
        thumbnail.onRetire { retirement.countDown() }
        val result = AtomicReference<AnimationAdmission.Lease?>()
        val viewer = Thread { result.set(admission.acquire(false, 1000)) }
        viewer.start()
        check(retirement.await(1, TimeUnit.SECONDS))
        check(admission.acquire(true, 100) == null)
        check(result.get() == null) // Retirement is not actual process death.
        thumbnail.release() // Simulated system-confirmed death boundary.
        viewer.join(1000)
        val interactive = checkNotNull(result.get())
        check(admission.acquire(true, 100) == null)
        interactive.release()
        interactive.release() // Duplicate death cannot manufacture a second permit.
        val next = checkNotNull(admission.acquire(true, 100))
        check(admission.acquire(true, 100) == null)
        next.release()

        val late = checkNotNull(admission.acquire(true, 100))
        val waiting = Thread { admission.acquire(false, 20)?.release() }
        waiting.start()
        waiting.join(1000)
        var requested = false
        late.onRetire { requested = true }
        check(requested) // Viewer arriving before callback installation still retires thumbnail.
        check(admission.acquire(true, 100) == null) // Timed-out viewer did not free live worker.
        late.release()
        checkNotNull(admission.acquire(true, 100)).release()
    }
}
