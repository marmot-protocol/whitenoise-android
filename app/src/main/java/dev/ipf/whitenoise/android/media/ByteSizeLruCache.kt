package dev.ipf.whitenoise.android.media

/**
 * A least-recently-used cache keyed by [K], whose capacity is bounded in
 * *bytes of value content* rather than entry count. Backed by a
 * `LinkedHashMap` in access-order: every [get] promotes the entry to MRU,
 * eviction starts from LRU.
 *
 * Extracted from the conversation controller so the eviction loop can be
 * tested directly with tiny caps — the previous inline implementation
 * called `iterator()` three times per eviction step, each returning a
 * fresh iterator, which threw `IllegalStateException` on the `remove()`
 * call (no preceding `next()` on that iterator). See the regression test
 * `evictsPastCap_withoutThrowing`.
 *
 * NOT thread-safe. The conversation controller funnels all access through
 * a single coroutine scope on the main dispatcher.
 */
class ByteSizeLruCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Int,
    private val maxEntryBytes: Long? = null,
    private val onEntryRemoved: (V) -> Unit = {},
    private val onEvicted: (V) -> Unit = {},
    // Entries whose removal frees no counted heap can opt out of capacity eviction; they still leave
    // through remove, replacement and clear.
    private val isEvictable: (V) -> Boolean = { true },
) {
    // accessOrder = true → LinkedHashMap iterates in LRU order for eviction.
    private val entries = LinkedHashMap<K, V>(8, 0.75f, true)
    private var residentBytes: Long = 0L

    /** The value for [key], promoted to most recently used. */
    fun get(key: K): V? = entries[key]

    // Every evictable entry is charged at least 1 byte: a 0 or negative sizeOf
    // would break the cap invariant (entries that never count toward eviction),
    // so the cache could grow without bound. Clamp here, in one place. An entry
    // that opted out of eviction holds no counted heap and is charged nothing,
    // so it can never make an evictable entry evict itself on insert.

    /** Bytes [value] counts against the cap: at least 1 when evictable, nothing when it opted out. */
    private fun chargeOf(value: V): Long =
        if (isEvictable(value)) {
            sizeOf(value).coerceAtLeast(1).toLong()
        } else {
            0L
        }

    /**
     * Inserts or replaces an entry. Updates resident-byte accounting,
     * promotes the entry to MRU, and evicts LRU entries until total
     * resident bytes are within the cap.
     */
    fun put(
        key: K,
        value: V,
    ): V? {
        val valueCharge = chargeOf(value)
        val entryCap = maxEntryBytes
        if (entryCap != null && valueCharge > entryCap) {
            val removed = entries.remove(key)
            if (removed != null) {
                residentBytes -= chargeOf(removed)
                onEntryRemoved(removed)
            }
            return removed
        }
        val previous = entries.put(key, value)
        if (previous != null) {
            residentBytes -= chargeOf(previous)
            if (previous !== value) onEntryRemoved(previous)
        }
        residentBytes += valueCharge
        evictUntilUnderCap()
        return previous
    }

    /** Removes [key] if present, updating byte accounting. Returns the value. */
    fun remove(key: K): V? {
        val removed = entries.remove(key)
        if (removed != null) {
            residentBytes -= chargeOf(removed)
            onEntryRemoved(removed)
        }
        return removed
    }

    fun clear() {
        entries.values.forEach(onEntryRemoved)
        entries.clear()
        residentBytes = 0L
    }

    fun size(): Int = entries.size

    /**
     * Snapshot of the current keys. Used by callers that need to filter
     * eviction by external criteria (e.g. skip entries whose work is still
     * in flight). Iterating `entries` directly would expose the LRU
     * mutation hazard; this snapshot is safe to traverse while mutating.
     */
    fun keysSnapshot(): List<K> = entries.keys.toList()

    /** Bytes currently counted against the cap. */
    fun residentBytes(): Long = residentBytes

    /** Removes least recently used evictable entries and reports only capacity-driven removals. */
    private fun evictUntilUnderCap() {
        if (residentBytes <= maxBytes) return
        // CRITICAL: hold a *single* iterator across the whole loop. Each
        // `entries.iterator()` (or `entries.entries.iterator()`) returns a
        // fresh iterator; calling `.remove()` on a fresh iterator throws
        // `IllegalStateException` because no `.next()` has advanced it.
        val it = entries.entries.iterator()
        while (it.hasNext() && residentBytes > maxBytes) {
            val eldest = it.next()
            if (!isEvictable(eldest.value)) continue
            residentBytes -= chargeOf(eldest.value)
            onEntryRemoved(eldest.value)
            onEvicted(eldest.value)
            it.remove()
        }
    }
}
