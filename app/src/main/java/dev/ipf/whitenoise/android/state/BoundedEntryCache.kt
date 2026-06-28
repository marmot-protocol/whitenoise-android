package dev.ipf.whitenoise.android.state

/**
 * Small access-order LRU for process-lifetime metadata caches.
 *
 * Callers decide their own synchronization; this class intentionally mirrors a
 * mutable map's tiny surface without adding another lock layer.
 */
internal class BoundedEntryCache<K : Any, V : Any>(
    private val maxEntries: Int,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val entries =
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
        }

    operator fun get(key: K): V? = entries[key]

    fun put(
        key: K,
        value: V,
    ): V? = entries.put(key, value)

    fun containsKey(key: K): Boolean = entries.containsKey(key)

    fun clear() {
        entries.clear()
    }

    fun size(): Int = entries.size
}
