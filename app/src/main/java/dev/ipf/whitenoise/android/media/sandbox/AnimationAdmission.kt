package dev.ipf.whitenoise.android.media.sandbox

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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

    inner class Lease internal constructor(
        internal val decorative: Boolean,
    ) {
        private var retirementRequested = false
        private var retirement: (() -> Unit)? = null
        private var released = false

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
