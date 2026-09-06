package dev.ipf.whitenoise.android.state

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic latest-wins boundary for work that suspends or crosses a callback.
 * Captures and equality checks are lock-free. Advancing and [runIfCurrent]
 * share a monitor so an accepted publication cannot interleave with invalidation.
 */
internal class StalenessGuard {
    private val generation = AtomicLong(0L)
    private val publicationLock = Any()

    /** Returns the generation that asynchronous work must retain until publication. */
    fun capture(): Long = generation.get()

    /** Invalidates earlier captures and returns the newly current generation. */
    fun advance(): Long = advance {}

    /**
     * Runs [invalidation] while guarded publications are excluded, then makes
     * every earlier capture stale before releasing the boundary.
     */
    fun advance(invalidation: () -> Unit): Long =
        synchronized(publicationLock) {
            invalidation()
            generation.incrementAndGet()
        }

    /** Advances only when [captured] still owns publication, otherwise returns null. */
    fun advanceIfCurrent(
        captured: Long,
        invalidation: () -> Unit = {},
    ): Long? =
        synchronized(publicationLock) {
            if (generation.get() != captured) return@synchronized null
            invalidation()
            generation.incrementAndGet()
        }

    /** Reports whether [captured] still identifies the newest requested work. */
    fun isCurrent(captured: Long): Boolean = generation.get() == captured

    /**
     * Runs [publication] only while [captured] is current, serialized with
     * [advance], and reports whether the publication was accepted.
     */
    fun runIfCurrent(
        captured: Long,
        publication: () -> Unit,
    ): Boolean =
        synchronized(publicationLock) {
            if (generation.get() != captured) return@synchronized false
            publication()
            true
        }
}
