package dev.ipf.whitenoise.android.state

/**
 * Account-scoped LRU cache for pure hex -> npub conversions.
 *
 * The conversion is cheap enough to recompute on a miss, but bounded retention
 * prevents relay-controlled ids from growing process memory for the full app
 * lifetime. The backing [ScopedCache] also guarantees account-boundary wipes.
 */
internal class BoundedNpubCache(
    registry: ScopedCacheRegistry,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries =
        ScopedCache<String, String>(
            registry = registry,
            name = "npubs",
            maxEntries = maxEntries,
        )

    fun get(accountIdHex: String): String? = entries[accountIdHex]

    fun put(
        accountIdHex: String,
        npub: String,
    ) {
        entries.put(accountIdHex, npub)
    }

    fun clear() = entries.clear()

    fun size(): Int = entries.size

    companion object {
        const val DEFAULT_MAX_ENTRIES = 4096
    }
}
