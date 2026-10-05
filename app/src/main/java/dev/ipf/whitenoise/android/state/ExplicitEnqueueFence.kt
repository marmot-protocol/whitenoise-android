package dev.ipf.whitenoise.android.state

import android.os.SystemClock

/**
 * Lets one explicit request at a time decide how to replace automatic work for an attachment.
 *
 * The decision reads a snapshot of existing work asynchronously and then enqueues. Two requests that
 * read the same automatic work would both replace it, and the second replacement would interrupt the
 * explicit worker the first one just created. A request that arrives while another holds the fence is
 * dropped, because the holder enqueues the same spec and the intent is already recorded.
 *
 * Every acquisition returns a [Lease] with its own token. An entry older than [staleAfterMillis] is
 * ignored so a lookup that never returns cannot block explicit requests for the rest of the process,
 * and the successor gets a new token. A holder that was superseded can then neither release the
 * successor's lease nor settle its stale snapshot: [runIfCurrent] runs the settlement only while the
 * lease is still the current one, under the same lock that takeover uses.
 */
internal class ExplicitEnqueueFence(
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
) {
    /** Proof that the holder owns the decision for [name], valid until released or superseded. */
    internal class Lease internal constructor(
        internal val name: String,
        internal val token: Long,
    )

    private class Held(
        val token: Long,
        val since: Long,
    )

    private val held = HashMap<String, Held>()
    private var lastToken = 0L

    /** Returns a lease when the caller now owns the decision for [name], or null when an earlier request does. */
    @Synchronized
    fun tryAcquire(name: String): Lease? {
        val now = nowMillis()
        val current = held[name]
        if (current != null && now - current.since < staleAfterMillis) return null
        lastToken += 1
        held[name] = Held(lastToken, now)
        return Lease(name, lastToken)
    }

    /** True while [lease] is still the current owner of its attachment's decision. */
    @Synchronized
    fun isCurrent(lease: Lease): Boolean = held[lease.name]?.token == lease.token

    /** Runs [block] only while [lease] is current, serialized with takeover, and returns whether it ran. */
    @Synchronized
    fun runIfCurrent(
        lease: Lease,
        block: () -> Unit,
    ): Boolean {
        if (!isCurrent(lease)) return false
        block()
        return true
    }

    /** Releases [lease] once its decision has been enqueued or abandoned, unless a successor took over. */
    @Synchronized
    fun release(lease: Lease) {
        if (held[lease.name]?.token == lease.token) held.remove(lease.name)
    }

    private companion object {
        const val DEFAULT_STALE_AFTER_MILLIS = 30_000L
    }
}
