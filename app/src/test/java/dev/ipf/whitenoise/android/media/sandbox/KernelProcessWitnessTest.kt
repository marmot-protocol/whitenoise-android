package dev.ipf.whitenoise.android.media.sandbox

import android.system.ErrnoException
import android.system.OsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Injected kernel results prove the admission rule, not real Android process termination. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class KernelProcessWitnessTest {
    @Test
    fun onlyCalibratedEsrchReleasesTheExactProcess() {
        var errno = OsConstants.EPERM
        val witness =
            KernelProcessWitness(100, 200) { pid, signal ->
                assertEquals(201, pid)
                assertEquals(0, signal)
                if (errno != 0) throw ErrnoException("kill", errno)
            }
        assertTrue(witness.admit(101, 201))
        assertTrue(witness.acceptsUid(101))
        assertTrue(witness.acceptsCaller(101, 201))
        assertFalse(witness.acceptsCaller(101, 0))
        assertFalse(witness.acceptsCaller(101, 202))
        assertFalse(witness.acceptsUid(102))
        for (held in listOf(0, OsConstants.EPERM, OsConstants.EACCES, OsConstants.ENOSYS, OsConstants.EINVAL)) {
            errno = held
            assertFalse(witness.confirmedGone())
        }
        errno = OsConstants.ESRCH
        assertTrue(witness.confirmedGone())
        errno = OsConstants.EPERM // A reused PID cannot resurrect the original process after witnessed absence.
        assertTrue(witness.confirmedGone())
        assertFalse(witness.admit(102, 202))
    }

    @Test
    fun calibrationRefusesVendorAlwaysEsrchAndUnknownSyscalls() {
        for (errno in listOf(OsConstants.ESRCH, OsConstants.ENOSYS, OsConstants.EINVAL)) {
            val witness = KernelProcessWitness(100, 200) { _, _ -> throw ErrnoException("kill", errno) }
            assertFalse(witness.admit(101, 201))
            assertFalse(witness.acceptsUid(101))
            assertFalse(witness.confirmedGone())
        }
    }

    @Test
    fun invalidAndOwnIdentitiesNeverInvokeAKernelSignal() {
        var calls = 0
        val witness = KernelProcessWitness(100, 200) { _, _ -> calls++ }
        for ((uid, pid) in listOf(100 to 201, 101 to 200, 101 to 0, 101 to -1, 0 to 201)) {
            assertFalse(witness.admit(uid, pid))
        }
        assertEquals(0, calls)
        assertFalse(witness.confirmedGone())
    }

    @Test
    fun binderDeathAndAccountReplacementWaitForKernelAbsenceAndCleanup() {
        var alive = true
        val witness =
            KernelProcessWitness(100, 200) { _, _ ->
                throw ErrnoException("kill", if (alive) OsConstants.EPERM else OsConstants.ESRCH)
            }
        val lifetime = AvStreamLifetime()
        val admissions = AvStreamAdmissions<Any>()
        val old = Any()
        val epoch = admissions.epoch()
        admissions.reserve(AvStreamLane.Playback, epoch) { old }
        assertTrue(witness.admit(101, 201))
        lifetime.bindCompleted(true)
        lifetime.beginCall()
        lifetime.close()
        assertTrue(lifetime.beginUnbind())
        lifetime.unbindFinished(true)
        lifetime.deathObserved()
        admissions.invalidateAll()
        assertFalse(witness.confirmedGone())
        assertFalse(lifetime.releasable())
        assertTrue(admissions.current(AvStreamLane.Playback, admissions.epoch()) === old)
        alive = false
        if (witness.confirmedGone()) lifetime.processGoneObserved()
        assertFalse(lifetime.releasable()) // Caller still owns its bounded raw-output copy.
        lifetime.endCall()
        assertTrue(lifetime.releasable())
        admissions.release(AvStreamLane.Playback, old)
        val replacement = Any()
        admissions.reserve(AvStreamLane.Playback, admissions.epoch()) { replacement }
        assertTrue(admissions.isCurrent(AvStreamLane.Playback, admissions.epoch(), replacement))
        val nextWitness = KernelProcessWitness(100, 200) { _, _ -> throw ErrnoException("kill", OsConstants.EPERM) }
        assertTrue(nextWitness.admit(102, 202))
        assertFalse(witness.acceptsUid(102))
        assertTrue(nextWitness.acceptsUid(102))
    }
}
