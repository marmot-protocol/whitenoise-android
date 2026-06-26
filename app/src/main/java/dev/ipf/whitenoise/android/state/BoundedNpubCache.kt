package dev.ipf.whitenoise.android.state

/**
 * Thread-safe LRU cache for pure hex -> npub conversions.
 *
 * The conversion is cheap enough to recompute on a miss, but unbounded retention
 * lets relay-controlled ids grow process memory for the full app lifetime.
 */
internal class BoundedNpubCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val lock = Any()
    private val entries =
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > maxEntries
        }

    fun get(accountIdHex: String): String? = synchronized(lock) { entries[accountIdHex] }

    fun put(
        accountIdHex: String,
        npub: String,
    ) {
        synchronized(lock) {
            entries[accountIdHex] = npub
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 4096
    }
}
