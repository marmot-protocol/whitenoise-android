package dev.ipf.whitenoise.android.notifications

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps notification writes under Android's per-app notification rate limit.
 *
 * NotificationManagerService sheds `notify` and `cancel` calls above roughly five per second per package,
 * silently: a shed update leaves the old card content on screen and a shed cancel leaves a card the app
 * believes it removed. Bursts of live updates for one busy conversation reached eight to eleven calls per
 * second in the field. This is a token bucket: a short burst passes untouched, and beyond it every write
 * waits for the next token instead of being dropped. A superseded write is still skipped afterwards by the
 * card's own generation checks, so waiting never changes what ends up on screen, only when.
 */
class NotificationPostPacer(
    private val refillIntervalMillis: Long = REFILL_INTERVAL_MILLIS,
    private val burstCapacity: Int = BURST_CAPACITY,
    private val nowMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var tokens = burstCapacity.toDouble()
    private var lastRefillAtMillis = Long.MIN_VALUE

    /** Takes one write token, waiting for the bucket to refill when a burst has used them up; returns the wait. */
    suspend fun awaitSlot(): Long {
        val wait =
            mutex.withLock {
                val now = nowMillis()
                if (lastRefillAtMillis == Long.MIN_VALUE) lastRefillAtMillis = now
                refill(now)
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    val wait = ((1.0 - tokens) * refillIntervalMillis).toLong().coerceAtLeast(1L)
                    tokens -= 1.0
                    wait
                }
            }
        if (wait > 0) sleep(wait)
        return wait
    }

    private fun refill(now: Long) {
        val elapsed = (now - lastRefillAtMillis).coerceAtLeast(0L)
        if (elapsed <= 0L) return
        tokens = minOf(burstCapacity.toDouble(), tokens + elapsed.toDouble() / refillIntervalMillis)
        lastRefillAtMillis = now
    }

    companion object {
        /** Five writes per second is the platform cap; one token every 220 ms keeps a margin under it. */
        const val REFILL_INTERVAL_MILLIS = 220L

        /** Writes that may go out back to back before pacing starts; the platform tolerates a short burst. */
        const val BURST_CAPACITY = 4
        private const val NANOS_PER_MILLI = 1_000_000L

        /** The process-wide pacer every presenter shares, because the platform limit is per package. */
        val shared = NotificationPostPacer()
    }
}
