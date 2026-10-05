package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets one explicit request at a time decide how to replace automatic work for an attachment.
 *
 * The decision reads a snapshot of existing work asynchronously and then enqueues. Two requests that
 * read the same automatic work would both replace it, and the second replacement would interrupt the
 * explicit worker the first one just created. A request that arrives while another still holds the
 * fence is dropped, because the holder enqueues the same spec and the intent is already recorded.
 * An entry older than [staleAfterMillis] is ignored so a lookup that never returns cannot block
 * explicit requests for the rest of the process.
 */
internal class ExplicitEnqueueFence(
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
) {
    private val heldSince = ConcurrentHashMap<String, Long>()

    /** Returns true when the caller now owns the decision for [name], false when an earlier request does. */
    fun tryAcquire(name: String): Boolean {
        var acquired = false
        heldSince.compute(name) { _, since ->
            val now = nowMillis()
            if (since == null || now - since >= staleAfterMillis) {
                acquired = true
                now
            } else {
                since
            }
        }
        return acquired
    }

    /** Releases [name] once its decision has been enqueued or abandoned. */
    fun release(name: String) {
        heldSince.remove(name)
    }

    private companion object {
        const val DEFAULT_STALE_AFTER_MILLIS = 30_000L
    }
}
