package dev.ipf.whitenoise.android.state

import java.util.IdentityHashMap

/**
 * Owns bounded in-memory caches that must be wiped together at an account boundary.
 *
 * Registrations happen in each cache constructor, so adding another scoped cache
 * cannot silently omit it from [clearAll]. The registry is owned by AppState rather
 * than process-global so it cannot retain a discarded AppState instance.
 */
internal class ScopedCacheRegistry {
    private val registrationLock = Any()
    private val registrations = linkedMapOf<String, ScopedCacheRegistration>()

    internal fun register(registration: ScopedCacheRegistration) {
        require(registration.name.isNotBlank()) { "Scoped cache name must not be blank" }
        synchronized(registrationLock) {
            check(registrations.putIfAbsent(registration.name, registration) == null) {
                "Scoped cache name already registered: ${registration.name}"
            }
        }
    }

    fun clearAll() {
        val snapshot = synchronized(registrationLock) { registrations.values.toList() }
        val byLock = IdentityHashMap<Any, MutableList<ScopedCacheRegistration>>()
        snapshot.forEach { registration ->
            byLock.getOrPut(registration.lock) { mutableListOf() }.add(registration)
        }
        byLock.values.forEach { group ->
            synchronized(group.first().lock) { group.forEach(ScopedCacheRegistration::clearLocked) }
        }
    }
}

internal interface ScopedCacheRegistration {
    val name: String
    val lock: Any

    /** Called by [ScopedCacheRegistry] while holding [lock]. */
    fun clearLocked()
}

/**
 * Thread-safe access-order LRU that registers its account-boundary clear path at
 * construction.
 */
internal class ScopedCache<K : Any, V : Any>(
    registry: ScopedCacheRegistry,
    override val name: String,
    private val maxEntries: Int,
    override val lock: Any = Any(),
) : ScopedCacheRegistration {
    private val entries =
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
        }

    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
        registry.register(this)
    }

    operator fun get(key: K): V? = synchronized(lock) { entries[key] }

    fun put(
        key: K,
        value: V,
    ): V? = synchronized(lock) { entries.put(key, value) }

    fun getOrPut(
        key: K,
        defaultValue: () -> V,
    ): V = synchronized(lock) { entries.getOrPut(key, defaultValue) }

    fun remove(key: K): V? = synchronized(lock) { entries.remove(key) }

    fun containsKey(key: K): Boolean = synchronized(lock) { entries.containsKey(key) }

    val size: Int
        get() = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    override fun clearLocked() {
        entries.clear()
    }
}

/** Set-shaped view backed by a registered [ScopedCache]. */
internal class ScopedSet<E : Any>(
    registry: ScopedCacheRegistry,
    name: String,
    maxEntries: Int,
    lock: Any = Any(),
) {
    private val entries = ScopedCache<E, Unit>(registry, name, maxEntries, lock)

    fun add(value: E): Boolean = entries.put(value, Unit) == null

    fun remove(value: E): Boolean = entries.remove(value) != null

    operator fun contains(value: E): Boolean = entries.containsKey(value)

    val size: Int
        get() = entries.size

    fun clear() = entries.clear()
}
