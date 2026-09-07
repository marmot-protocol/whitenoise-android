package dev.ipf.whitenoise.android.notifications

/**
 * Tracks capability-fallback generations attached to one foreground-service
 * instance. A request arriving during an existing bootstrap joins that attempt;
 * one successful supervisor boundary acknowledges the current latest-wins
 * generation, including a request received after the boundary while the service
 * job is still completing. Superseded history is evicted eagerly.
 */
internal class CapabilityFallbackServiceRequests {
    private var registered: Long? = null
    private var acknowledged: Long? = null
    private var runtimeStarted = false

    /** Registers a positive generation and returns it immediately when this instance is already ready. */
    fun register(generation: Long?): Set<Long> =
        if (generation == null || generation <= 0L) {
            emptySet()
        } else {
            registered = generation
            acknowledged = null
            if (runtimeStarted) {
                acknowledged = generation
                setOf(generation)
            } else {
                emptySet()
            }
        }

    /** Acknowledges the latest request currently attached to the successful runtime instance. */
    fun onRuntimeStarted(): Set<Long> {
        runtimeStarted = true
        val current = registered
        return if (current == null || acknowledged == current) {
            emptySet()
        } else {
            acknowledged = current
            setOf(current)
        }
    }

    /** Rejects one start without invalidating other requests attached to a live instance. */
    fun reject(generation: Long?): Set<Long> {
        if (generation == null || registered != generation) return emptySet()
        registered = null
        acknowledged = null
        return setOf(generation)
    }

    /** Invalidates every generation owned by an unavailable or destroyed service instance. */
    fun onRuntimeUnavailable(): Set<Long> {
        runtimeStarted = false
        val invalidated = registered?.let(::setOf).orEmpty()
        registered = null
        acknowledged = null
        return invalidated
    }
}
