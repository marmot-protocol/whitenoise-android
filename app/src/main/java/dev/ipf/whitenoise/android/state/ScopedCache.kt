package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
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
 *
 * An [observable] cache lets Compose readers re-read when their answer can change. Its revision advances only when
 * an entry a reader may hold is replaced, removed, evicted or cleared, or when a reader has seen a key missing (or
 * read the size) since the last advance. Filling a missing key that nobody saw missing is not a change, so the first
 * reader of a key never recomposes every other reader of the cache.
 */
internal class ScopedCache<K : Any, V : Any>(
    registry: ScopedCacheRegistry,
    override val name: String,
    maxEntries: Int,
    override val lock: Any = Any(),
    observable: Boolean = false,
) : ScopedCacheRegistration {
    private val entries = BoundedEntryCache<K, V>(maxEntries)
    private val observableRevision: MutableIntState? = if (observable) mutableIntStateOf(0) else null

    // A reader that saw a key missing, or read the size, may fall back to a source outside this cache, so any later
    // mutation must reach it. Cleared each time the revision advances.
    private var absenceObserved = false

    init {
        registry.register(this)
    }

    /** Returns the cached value, or null after noting that a reader saw [key] missing. */
    operator fun get(key: K): V? =
        synchronized(lock) {
            entries[key].also { value -> observeRevisionLocked(missing = value == null) }
        }

    /** Stores [value], advancing the revision only when a held entry changes or a reader saw a key missing. */
    fun put(
        key: K,
        value: V,
    ): V? =
        synchronized(lock) {
            val sizeBefore = entries.size()
            entries.put(key, value).also { previous ->
                when {
                    // A new key that evicted another drops an entry some reader may hold.
                    previous == null -> changedLocked(entriesChanged = entries.size() <= sizeBefore)
                    previous != value -> advanceRevisionLocked()
                }
            }
        }

    /** Returns the cached value, filling a missing key without disturbing readers of other keys. */
    fun getOrPut(
        key: K,
        defaultValue: () -> V,
    ): V =
        synchronized(lock) {
            entries[key]?.also {
                observeRevisionLocked()
            } ?: defaultValue().also { value ->
                val sizeBefore = entries.size()
                entries.put(key, value)
                // An eviction drops an entry some reader may hold, while a plain fill changes nothing anyone read.
                changedLocked(entriesChanged = entries.size() <= sizeBefore)
                observeRevisionLocked()
            }
        }

    /** Removes [key], advancing the revision when it held an entry or a reader saw a key missing. */
    fun remove(key: K): V? =
        synchronized(lock) {
            entries.remove(key).also { removed -> changedLocked(removed != null) }
        }

    /** Removes matching keys, advancing the revision when any matched or a reader saw a key missing. */
    fun removeAll(predicate: (K) -> Boolean): Boolean =
        synchronized(lock) {
            entries.removeAll(predicate).also(::changedLocked)
        }

    /** Whether [key] is cached, noting a miss so a later fill reaches this reader. */
    fun containsKey(key: K): Boolean =
        synchronized(lock) {
            entries.containsKey(key).also { present -> observeRevisionLocked(missing = !present) }
        }

    val size: Int
        get() =
            synchronized(lock) {
                // Any insertion changes the size, so a size reader is treated like one that saw a key missing.
                entries.size().also { observeRevisionLocked(missing = true) }
            }

    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    /** Drops every entry, invalidating readers only when one was held or a key was seen missing. */
    override fun clearLocked() {
        val hadEntries = entries.size() > 0
        entries.clear()
        changedLocked(hadEntries)
    }

    /** Advances when held entries changed, or when a reader saw a key missing and may depend on what changed. */
    private fun changedLocked(entriesChanged: Boolean) {
        if (entriesChanged || absenceObserved) advanceRevisionLocked()
    }

    /** Subscribes the current reader to the revision, noting when it saw a key [missing]. */
    private fun observeRevisionLocked(missing: Boolean = false) {
        val revision = observableRevision ?: return
        if (missing) absenceObserved = true
        revision.intValue
    }

    /** Invalidates every reader of the revision and forgets which keys were seen missing. */
    private fun advanceRevisionLocked() {
        val revision = observableRevision ?: return
        absenceObserved = false
        revision.intValue += 1
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
