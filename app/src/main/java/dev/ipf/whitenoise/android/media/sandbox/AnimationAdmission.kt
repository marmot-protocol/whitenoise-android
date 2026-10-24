package dev.ipf.whitenoise.android.media.sandbox

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** One actual worker; decorative use never queues ahead of interactive media. */
internal class AnimationAdmission {
    private val permit = Semaphore(1, true)
    private val lock = Any()
    private var interactiveWaiters = 0
    private var current: Lease? = null

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
            synchronized(lock) { interactiveWaiters-- }
        }
    }

    inner class Lease internal constructor(
        internal val decorative: Boolean,
    ) {
        private var retirementRequested = false
        private var retirement: (() -> Unit)? = null
        private var released = false

        fun onRetire(callback: () -> Unit) {
            val requested =
                synchronized(lock) {
                    retirement = callback
                    retirementRequested
                }
            if (requested) callback()
        }

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
            }
        }
    }
}
