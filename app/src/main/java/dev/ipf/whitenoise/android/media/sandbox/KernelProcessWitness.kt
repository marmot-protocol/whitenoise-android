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
        if (identity != null || uid <= 0 || uid == ownUid || pid <= 0 || pid == ownPid) return false
        identity = uid to pid
        calibrated = observe(pid) == Presence.Alive
        return calibrated
    }

    @Synchronized fun acceptsUid(uid: Int): Boolean = calibrated && identity?.first == uid

    @Synchronized fun acceptsCaller(
        uid: Int,
        pid: Int,
    ): Boolean = calibrated && identity?.first == uid && identity?.second == pid

    /** PID reuse, zombies, permission denial and unknown errors remain held without a TTL. */
    @Synchronized fun confirmedGone(): Boolean {
        if (gone) return true
        val pid = identity?.second ?: return false
        if (!calibrated) return false
        gone = observe(pid) == Presence.Gone
        return gone
    }

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
