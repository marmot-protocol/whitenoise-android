package dev.ipf.whitenoise.android.media.sandbox

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** One actual worker; decorative use never queues ahead of interactive media. */
internal class AnimationAdmission {
    private val permit = Semaphore(1, true)
    private val lock = Object()
    private var interactiveWaiters = 0
    private var current: Lease? = null

    /** Opportunistic thumbnails never take admission ahead of a waiting explicit viewer. */
    fun acquire(
        decorative: Boolean,
        timeoutMillis: Long,
    ): Lease? {
        if (decorative) {
            return synchronized(lock) {
                if (interactiveWaiters != 0 || !permit.tryAcquire()) null else Lease(true).also { current = it }
            }
        }
        val retire =
            synchronized(lock) {
                interactiveWaiters++
                current?.takeIf { it.decorative }
            }
        return try {
            retire?.retire()
            if (permit.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
                synchronized(lock) { Lease(false).also { current = it } }
            } else {
                null
            }
        } finally {
            synchronized(lock) {
                interactiveWaiters--
                lock.notifyAll()
            }
        }
    }

    /**
     * Waits without polling for confirmed worker death and all interactive waiters. Group thumbnails
     * call this only inside their three-source budget and an interruptible, lifecycle-owned IO job.
     * Cancellation interrupts the wait without acquiring a lease or starting another worker.
     */
    fun awaitDecorative(): Lease =
        synchronized(lock) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("group playback cancelled")
            while (interactiveWaiters != 0 || !permit.tryAcquire()) lock.wait()
            Lease(true).also { current = it }
        }

    /**
     * Retries an interrupted group startup after viewer takeover, without retrying codec failures.
     * Each attempt owns its lease cleanup; a retry still waits for confirmed death and viewer release.
     */
    fun <T : Any> decode(
        decorative: Boolean,
        waitForDecorativeAdmission: Boolean,
        timeoutMillis: Long,
        open: (Lease) -> T?,
    ): T? {
        val waitingGroup = decorative && waitForDecorativeAdmission
        do {
            val lease = if (waitingGroup) awaitDecorative() else acquire(decorative, timeoutMillis) ?: return null
            val result = openAttempt(lease, waitingGroup, open)
            if (result != null || !waitingGroup || !lease.wasPreempted()) return result
        } while (true)
    }

    /** A retired startup may throw while marshalling closed capabilities; cancellation always escapes. */
    @Suppress("TooGenericExceptionCaught") // Only an explicit viewer retirement makes startup failure retryable.
    private fun <T : Any> openAttempt(
        lease: Lease,
        waitingGroup: Boolean,
        open: (Lease) -> T?,
    ): T? =
        try {
            open(lease)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (failure: Exception) {
            if (!waitingGroup || !lease.wasPreempted()) throw failure
            null
        }

    inner class Lease internal constructor(
        internal val decorative: Boolean,
    ) {
        private var retirementRequested = false
        private var retirement: (() -> Unit)? = null
        private var released = false

        /** The request is recorded before its callback can close a startup with no drawable yet. */
        internal fun wasPreempted(): Boolean = synchronized(lock) { retirementRequested }

        /** Delivers a viewer's retirement request even if it arrived before callback installation. */
        fun onRetire(callback: () -> Unit) {
            val requested =
                synchronized(lock) {
                    retirement = callback
                    retirementRequested
                }
            if (requested) callback()
        }

        /** Requests shutdown; only confirmed death may release this lease. */
        internal fun retire() {
            val callback =
                synchronized(lock) {
                    retirementRequested = true
                    retirement
                }
            callback?.invoke()
        }

        /** Called only after confirmed worker death, or before any binding started. */
        fun release() {
            synchronized(lock) {
                if (released) return
                released = true
                if (current === this) current = null
                permit.release()
                lock.notifyAll()
            }
        }
    }
}
