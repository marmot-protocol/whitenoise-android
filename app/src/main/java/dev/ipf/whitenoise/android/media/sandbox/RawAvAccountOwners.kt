package dev.ipf.whitenoise.android.media.sandbox

/** All prepared, paused and playing owners share one account epoch; focus activation is separate. */
internal class RawAvAccountOwners<T : Any> {
    data class Claim<T>(
        val accepted: Boolean,
        val previous: T?,
    )

    data class Retired<T>(
        val instances: List<T>,
        val active: T?,
    )

    private var epoch = 0L
    private val instances = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>())
    private var active: T? = null

    /**
     * Snapshots the account epoch for a subsequent registration; retirement invalidates this value.
     */
    @Synchronized fun epoch(): Long = epoch

    /**
     * Registers by object identity only while [expectedEpoch] is current; stale preparation is refused.
     */
    @Synchronized fun register(
        owner: T,
        expectedEpoch: Long,
    ): Boolean {
        if (epoch != expectedEpoch) return false
        instances.add(owner)
        return true
    }

    /**
     * Tests both epoch and identity membership while holding the registry monitor.
     */
    @Synchronized fun current(
        owner: T,
        expectedEpoch: Long,
    ): Boolean = epoch == expectedEpoch && instances.contains(owner)

    /**
     * Claims active ownership for a current registered instance and returns the previous active owner.
     * The caller retires or pauses that owner outside this monitor.
     */
    @Synchronized fun activate(
        owner: T,
        expectedEpoch: Long,
    ): Claim<T> {
        if (!current(owner, expectedEpoch)) return Claim(false, null)
        return Claim(true, active).also { active = owner }
    }

    /**
     * Drops identity membership and clears active ownership only for this exact instance.
     * Returns whether the removed instance held active ownership.
     */
    @Synchronized fun remove(owner: T): Boolean {
        instances.remove(owner)
        val wasActive = active === owner
        if (wasActive) active = null
        return wasActive
    }

    /** The action never acquires an engine lock. Callers acquire their engine lock first. */
    @Synchronized fun withCurrent(
        owner: T,
        expectedEpoch: Long,
        action: () -> Unit,
    ): Boolean {
        if (!current(owner, expectedEpoch)) return false
        action()
        return true
    }

    /**
     * Runs [action] under the registry monitor only for the exact active owner.
     * The action must preserve the engine-before-registry lock order and must not acquire an engine lock.
     */
    @Synchronized fun withActive(
        owner: T,
        action: () -> Unit,
    ) {
        if (active === owner) action()
    }

    /**
     * Runs [action] under the registry monitor only while no owner is active.
     * The action must not acquire an engine lock.
     */
    @Synchronized fun withNoActive(action: () -> Unit) {
        if (active == null) action()
    }

    /** Revocation is atomic. Callers close the returned owners only after leaving this monitor. */
    @Synchronized fun retireAll(): Retired<T> {
        epoch++
        val retired = Retired(instances.toList(), active)
        instances.clear()
        active = null
        return retired
    }
}
