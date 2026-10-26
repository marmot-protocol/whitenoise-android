package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.media.AnimationAdmissionBusyException
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** One actual worker; decorative use never queues ahead of interactive media. */
internal class AnimationAdmission {
    private val permit = Semaphore(1, true)
    private val lock = Object()
    private var interactiveWaiters = 0
    private var current: Lease? = null
    private val decorativeWaiters = ArrayDeque<Any>()
    private val availability = MutableStateFlow(0L)

    /** Opportunistic thumbnails never take admission ahead of a waiting explicit viewer. */
    fun acquire(
        decorative: Boolean,
        timeoutMillis: Long,
    ): Lease? {
        if (decorative) {
            return synchronized(lock) {
                if (interactiveWaiters != 0 || decorativeWaiters.isNotEmpty() || !permit.tryAcquire()) {
                    null
                } else {
                    Lease(true).also { current = it }
                }
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
                availability.value++
                lock.notifyAll()
            }
        }
    }

    /**
     * One finite, interruptible wait. FIFO groups cannot barge ahead of each other or explicit viewers.
     * Expiry is transient contention, never a codec failure or permission to release a live worker.
     */
    fun awaitDecorative(timeoutMillis: Long = MediaDecodePolicy.DEADLINE_MS): Lease =
        awaitDecorativeUntil(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis))

    private fun awaitDecorativeUntil(deadlineNanos: Long): Lease =
        synchronized(lock) {
            val observedGeneration = availability.value
            val waiter = Any()
            decorativeWaiters.addLast(waiter)
            try {
                var admitted: Lease? = null
                while (admitted == null) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("group playback cancelled")
                    val remaining = deadlineNanos - System.nanoTime()
                    if (remaining <= 0L) {
                        throw AnimationAdmissionBusyException(
                            availability,
                            observedGeneration,
                            interactiveWaiters == 0 && permit.availablePermits() > 0,
                        )
                    }
                    val first = decorativeWaiters.first() === waiter && interactiveWaiters == 0
                    if (first && permit.tryAcquire()) {
                        admitted = Lease(true).also { current = it }
                    } else {
                        TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                    }
                }
                admitted
            } finally {
                decorativeWaiters.remove(waiter)
                lock.notifyAll()
            }
        }

    /** Startup preemption shares one monotonic admission budget; genuine codec failures never retry. */
    fun <T : Any> decode(
        decorative: Boolean,
        waitForDecorativeAdmission: Boolean,
        timeoutMillis: Long,
        open: (Lease) -> T?,
    ): T? {
        val waitingGroup = decorative && waitForDecorativeAdmission
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        do {
            val lease =
                if (waitingGroup) awaitDecorativeUntil(deadline) else acquire(decorative, timeoutMillis) ?: return null
            val generation = availability.value
            val result = open(lease)
            if (result != null || !waitingGroup || !lease.wasPreempted()) return result
            if (System.nanoTime() - deadline >= 0L) {
                throw AnimationAdmissionBusyException(availability, generation)
            }
        } while (true)
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
                availability.value++
                lock.notifyAll()
            }
        }
    }
}
