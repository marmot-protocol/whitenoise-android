package dev.ipf.whitenoise.android.media.sandbox

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

/** Kernel callback identity plus a calibrated, signal-free process-absence observation. */
internal class KernelProcessWitness(
    private val ownUid: Int = Process.myUid(),
    private val ownPid: Int = Process.myPid(),
    private val signal: (Int, Int) -> Unit = Os::kill,
) {
    private var identity: Pair<Int, Int>? = null
    private var calibrated = false
    private var gone = false

    /** Called while the synchronous READY sender is blocked and must still be alive. */
    @Synchronized fun admit(
        uid: Int,
        pid: Int,
    ): Boolean {
        val foreignUid = uid > 0 && uid != ownUid
        val foreignPid = pid > 0 && pid != ownPid
        if (identity != null || !foreignUid || !foreignPid) return false
        identity = uid to pid
        calibrated = observe(pid) == Presence.Alive
        return calibrated
    }

    /** Matches the authenticated isolated UID; an uncalibrated witness cannot accept it. */
    @Synchronized fun acceptsUid(uid: Int): Boolean = calibrated && identity?.first == uid

    /** Matches both authenticated UID and PID so another process cannot reuse the reply capability. */
    @Synchronized fun acceptsCaller(
        uid: Int,
        pid: Int,
    ): Boolean = calibrated && identity?.first == uid && identity?.second == pid

    /** PID reuse, zombies, permission denial and unknown errors remain held without a TTL. */
    @Synchronized fun confirmedGone(): Boolean {
        if (!gone) {
            val pid = identity?.second
            if (pid != null && calibrated) gone = observe(pid) == Presence.Gone
        }
        return gone
    }

    /**
     * Observes the exact calibrated process with the kernel witness; Binder death alone is not process
     * absence.
     */
    private fun observe(pid: Int): Presence =
        try {
            signal(pid, 0)
            Presence.Alive
        } catch (failure: ErrnoException) {
            when (failure.errno) {
                OsConstants.ESRCH -> Presence.Gone
                OsConstants.EPERM, OsConstants.EACCES -> Presence.Alive
                else -> Presence.Unknown
            }
        } catch (_: RuntimeException) {
            Presence.Unknown
        } catch (_: LinkageError) {
            Presence.Unknown
        }

    private enum class Presence { Alive, Gone, Unknown }
}
