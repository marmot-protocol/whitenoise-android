package dev.ipf.whitenoise.android.notifications

import android.os.SystemClock

/** Posts landing this long after native catch-up settled still belong to its cohort, they were already in flight. */
internal const val NOTIFICATION_CATCH_UP_TAIL_MS = 5_000L

/** A catch-up open longer than this stops owning first posts, so a hung reconnect cannot silence the phone. */
internal const val NOTIFICATION_CATCH_UP_MAX_OPEN_MS = 2L * 60L * 1_000L

/**
 * The app-owned boundary of one bounded catch-up cohort (#1579). The catch-up coordinator opens it when
 * native catch-up work starts and closes it when that work settles, so every notification the engine
 * emits while a backlog drains, plus a short tail for posts already in flight, shares one generation.
 * Consecutive catch-ups that overlap the tail extend the same cohort. Nothing here inspects a message:
 * the boundary is the app's own catch-up call, not a guess from a timestamp.
 */
class NotificationCatchUpWindow(
    private val tailMs: Long = NOTIFICATION_CATCH_UP_TAIL_MS,
    private val maxOpenMs: Long = NOTIFICATION_CATCH_UP_MAX_OPEN_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()
    private var generation = 0L
    private var openedAtMs: Long? = null
    private var closedAtMs: Long? = null

    /** Native catch-up work started: a cohort begins, unless one is still current, which it then extends. */
    fun open() {
        synchronized(lock) {
            val now = clock()
            if (currentGenerationLocked(now) == null) generation += 1L
            openedAtMs = now
            closedAtMs = null
        }
    }

    /**
     * Native catch-up work settled, successfully or not: the cohort stays current for the tail only.
     * A catch-up that already ran past the cap gets no tail, its cohort was released while it hung.
     */
    fun close() {
        synchronized(lock) {
            val opened = openedAtMs ?: return
            val now = clock()
            openedAtMs = null
            closedAtMs = now.takeIf { now - opened <= maxOpenMs }
        }
    }

    /** The generation of the cohort a first post made now belongs to, or null between cohorts. */
    fun currentGeneration(): Long? = synchronized(lock) { currentGenerationLocked(clock()) }

    private fun currentGenerationLocked(nowMs: Long): Long? {
        val opened = openedAtMs
        val closed = closedAtMs
        val inside =
            when {
                opened != null -> nowMs - opened <= maxOpenMs
                closed != null -> nowMs - closed <= tailMs
                else -> false
            }
        return generation.takeIf { inside }
    }
}
