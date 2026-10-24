package dev.ipf.whitenoise.android.media.sandbox

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AnimationAdmissionTest {
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
