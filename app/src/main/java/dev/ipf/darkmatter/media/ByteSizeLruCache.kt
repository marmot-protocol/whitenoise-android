package dev.ipf.darkmatter.media

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
) {
    // accessOrder = true → LinkedHashMap iterates in LRU order for eviction.
    private val entries = LinkedHashMap<K, V>(8, 0.75f, true)
    private var residentBytes: Long = 0L

    fun get(key: K): V? = entries[key]

    // Every entry is charged at least 1 byte: a 0 or negative sizeOf would
    // break the cap invariant (entries that never count toward eviction), so
    // the cache could grow without bound. Clamp here, in one place.
    private fun chargeOf(value: V): Long = sizeOf(value).coerceAtLeast(1).toLong()

    /**
     * Inserts or replaces an entry. Updates resident-byte accounting,
     * promotes the entry to MRU, and evicts LRU entries until total
     * resident bytes are within the cap.
     */
    fun put(
        key: K,
        value: V,
    ): V? {
        val previous = entries.put(key, value)
        if (previous != null) residentBytes -= chargeOf(previous)
        residentBytes += chargeOf(value)
        evictUntilUnderCap()
        return previous
    }

    /** Removes [key] if present, updating byte accounting. Returns the value. */
    fun remove(key: K): V? {
        val removed = entries.remove(key)
        if (removed != null) residentBytes -= chargeOf(removed)
        return removed
    }

    fun clear() {
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

    fun residentBytes(): Long = residentBytes

    private fun evictUntilUnderCap() {
        if (residentBytes <= maxBytes) return
        // CRITICAL: hold a *single* iterator across the whole loop. Each
        // `entries.iterator()` (or `entries.entries.iterator()`) returns a
        // fresh iterator; calling `.remove()` on a fresh iterator throws
        // `IllegalStateException` because no `.next()` has advanced it.
        val it = entries.entries.iterator()
        while (it.hasNext() && residentBytes > maxBytes) {
            val eldest = it.next()
            residentBytes -= chargeOf(eldest.value)
            it.remove()
        }
    }
}
