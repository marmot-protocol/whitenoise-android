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

    @Synchronized fun epoch(): Long = epoch

    @Synchronized fun register(
        owner: T,
        expectedEpoch: Long,
    ): Boolean {
        if (epoch != expectedEpoch) return false
        instances.add(owner)
        return true
    }

    @Synchronized fun current(
        owner: T,
        expectedEpoch: Long,
    ): Boolean = epoch == expectedEpoch && instances.contains(owner)

    @Synchronized fun activate(
        owner: T,
        expectedEpoch: Long,
    ): Claim<T> {
        if (!current(owner, expectedEpoch)) return Claim(false, null)
        return Claim(true, active).also { active = owner }
    }

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

    @Synchronized fun withActive(
        owner: T,
        action: () -> Unit,
    ) {
        if (active === owner) action()
    }

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
