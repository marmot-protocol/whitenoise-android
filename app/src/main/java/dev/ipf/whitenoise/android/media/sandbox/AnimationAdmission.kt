package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.media.AnimationAdmissionBusyException
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** One physical worker; interactive > group thumbnail > opportunistic inline playback. */
internal class AnimationAdmission(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    internal enum class Purpose { Interactive, GroupThumbnail, Inline }

    private val permit = Semaphore(1, true)
    private val lock = Object()
    private var interactiveWaiters = 0
    private var current: Lease? = null
    private val pendingGroups = ArrayDeque<GroupDemand>()
    private val availability = MutableStateFlow(0L)

    /** A mounted source owner's FIFO demand survives finite IO waits and coroutine parking. */
    inner class GroupDemand internal constructor() : java.io.Closeable {
        private var closed = false
        private var coolingDown = false
        private var queued = false

        internal fun enqueue() {
            val retire =
                synchronized(lock) {
                    if (closed || coolingDown || queued) return@synchronized null
                    queued = true
                    pendingGroups.addLast(this)
                    changed()
                    current?.takeIf { it.purpose == Purpose.Inline }
                }
            retire?.retire()
        }

        /** Fault backoff must not reserve the worker ahead of healthy pending groups. */
        fun beginBackoff() =
            synchronized(lock) {
                if (closed) return@synchronized
                coolingDown = true
                unqueue()
                changed()
            }

        fun endBackoff() {
            synchronized(lock) {
                if (closed) return
                coolingDown = false
            }
            enqueue()
        }

        private fun unqueue() {
            pendingGroups.remove(this)
            queued = false
        }

        internal fun takeIfHead(): Boolean {
            if (closed || coolingDown || pendingGroups.firstOrNull() !== this) return false
            unqueue()
            return true
        }

        /** An expired startup acquired no capability; preserve its original FIFO position. */
        internal fun restoreHead() {
            if (!closed && !coolingDown && !queued) {
                queued = true
                pendingGroups.addFirst(this)
            }
        }

        internal fun isHead(): Boolean = !closed && !coolingDown && pendingGroups.firstOrNull() === this

        override fun close() =
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    unqueue()
                    changed()
                }
            }
    }

    fun groupDemand(): GroupDemand = GroupDemand().also { it.enqueue() }

    private fun changed() {
        availability.value++
        lock.notifyAll()
    }

    /** Inline callers never queue; only higher-priority demand can retire their lease. */
    fun acquire(
        decorative: Boolean,
        timeoutMillis: Long,
    ): Lease? = acquireUntil(decorative, nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis))

    private fun acquireUntil(
        decorative: Boolean,
        deadlineNanos: Long,
    ): Lease? {
        if (decorative) {
            return synchronized(lock) {
                if (interactiveWaiters != 0 || pendingGroups.isNotEmpty() || !permit.tryAcquire()) {
                    null
                } else {
                    Lease(Purpose.Inline, deadlineNanos).also { current = it }
                }
            }
        }
        val retire =
            synchronized(lock) {
                interactiveWaiters++
                current?.takeIf { it.purpose != Purpose.Interactive }
            }
        return try {
            retire?.retire()
            if (permit.tryAcquire((deadlineNanos - nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS)) {
                synchronized(lock) { Lease(Purpose.Interactive, deadlineNanos).also { current = it } }
            } else {
                null
            }
        } finally {
            synchronized(lock) {
                interactiveWaiters--
                changed()
            }
        }
    }

    /** Compatibility for finite callers; mounted group owners pass their persistent ticket instead. */
    fun awaitDecorative(timeoutMillis: Long = MediaDecodePolicy.DEADLINE_MS): Lease {
        val demand = groupDemand()
        return try {
            awaitDecorativeUntil(nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis), demand)
        } finally {
            demand.close()
        }
    }

    private fun awaitDecorativeUntil(
        deadlineNanos: Long,
        demand: GroupDemand,
    ): Lease {
        demand.enqueue()
        return synchronized(lock) {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("group playback cancelled")
                // Preserve a ready FIFO head even when the wake coincides with its blocking deadline.
                if (demand.isHead() && interactiveWaiters == 0 && permit.tryAcquire()) {
                    check(demand.takeIfHead())
                    return@synchronized Lease(Purpose.GroupThumbnail, deadlineNanos, demand).also { current = it }
                }
                val remaining = deadlineNanos - nanoTime()
                if (remaining <= 0L) {
                    throw AnimationAdmissionBusyException(
                        availability,
                        availability.value,
                        demand.isHead() && interactiveWaiters == 0 && permit.availablePermits() > 0,
                    )
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    /** Startup preemption has one monotonic budget, not one fresh deadline per OPEN. */
    fun <T : Any> decode(
        decorative: Boolean,
        waitForDecorativeAdmission: Boolean,
        timeoutMillis: Long,
        demand: GroupDemand? = null,
        open: (Lease) -> T?,
    ): T? {
        val waitingGroup = decorative && waitForDecorativeAdmission
        val ownedDemand = if (waitingGroup && demand == null) groupDemand() else null
        val ticket = demand ?: ownedDemand
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var result: T? = null
        var retry = true
        try {
            while (retry) {
                val lease = startupLease(decorative, deadline, if (waitingGroup) ticket else null) ?: break
                if (Thread.currentThread().isInterrupted) {
                    lease.release() // Cancellation before OPEN owns no worker capability.
                    throw InterruptedException("group playback cancelled before OPEN")
                }
                result = open(lease)
                retry = result == null && waitingGroup && lease.wasPreempted()
                if (retry) {
                    // Requeue at the tail without refreshing the total startup deadline.
                    checkNotNull(ticket).enqueue()
                    retry = !expireStartup(deadline, ticket)
                }
            }
            return result
        } finally {
            ownedDemand?.close()
        }
    }

    /** Both sides of acquisition share the original total budget; an unused grant never reaches OPEN. */
    private fun startupLease(
        decorative: Boolean,
        deadline: Long,
        ticket: GroupDemand?,
    ): Lease? {
        if (expireStartup(deadline, ticket)) return null
        val lease = if (ticket != null) awaitDecorativeUntil(deadline, ticket) else acquireUntil(decorative, deadline)
        return if (lease == null || expireStartup(deadline, ticket, lease)) null else lease
    }

    /** An expired total startup budget can never issue OPEN, even after a ready acquisition. */
    private fun expireStartup(
        deadline: Long,
        ticket: GroupDemand?,
        unused: Lease? = null,
    ): Boolean {
        if (nanoTime() - deadline < 0L) return false
        if (ticket == null) {
            unused?.release()
            return true
        }
        if (unused == null) ticket.enqueue()
        synchronized(lock) {
            if (unused != null) {
                ticket.restoreHead()
                unused.release() // No binding or OPEN was issued from this grant.
            }
            throw AnimationAdmissionBusyException(
                availability,
                availability.value,
                ticket.isHead() && interactiveWaiters == 0 && permit.availablePermits() > 0,
            )
        }
    }

    inner class Lease internal constructor(
        internal val purpose: Purpose,
        internal val deadlineNanos: Long,
        private val demand: GroupDemand? = null,
    ) {
        /** Same absolute admission deadline; session startup never receives a fresh duration. */
        internal fun remainingNanos(): Long = (deadlineNanos - nanoTime()).coerceAtLeast(0L)

        internal val decorative: Boolean get() = purpose != Purpose.Interactive
        private var retirementRequested = false
        private var retirement: (() -> Unit)? = null
        private var released = false

        internal fun wasPreempted(): Boolean = synchronized(lock) { retirementRequested }

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
                    if (retirementRequested) return
                    retirementRequested = true
                    retirement
                }
            // Requeue before the physical retirement callback can acknowledge death.
            demand?.enqueue()
            callback?.invoke()
        }

        /** Only confirmed worker death, or a proved no-bind path, may release physical capacity. */
        fun release() =
            synchronized(lock) {
                if (!released) {
                    released = true
                    if (current === this) current = null
                    permit.release()
                    changed()
                }
            }
    }
}
