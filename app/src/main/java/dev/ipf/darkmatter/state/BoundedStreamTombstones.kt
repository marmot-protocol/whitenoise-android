package dev.ipf.darkmatter.state

/**
 * A fixed-capacity, least-recently-used set of agent-stream tombstone ids.
 *
 * The conversation controller marks a stream id "removed" once its final
 * record lands so a late `AgentStreamUpdateFfi.Finished` event can't recreate
 * the optimistic preview as a duplicate (see #25). Previously these tombstones
 * lived in a plain `mutableSetOf<String>()` that was only ever shrunk by a
 * single re-add edge case, so a long-lived agent-heavy conversation
 * accumulated one string per stream for the controller's whole lifetime and
 * paid an ever-growing `filterNot { it in removedStreamIds }` cost on every
 * incoming page/change batch (see #200).
 *
 * Bounding the set caps both the memory footprint and the per-batch filter
 * cost. LRU eviction (access-order) keeps the *recently* removed/checked
 * streams tombstoned — those are exactly the ones a late duplicate event is
 * still likely to target — and evicts the oldest tombstones, whose streams
 * are long settled and out of the loaded window.
 *
 * NOT thread-safe. The conversation controller funnels all access through a
 * single coroutine scope on the main dispatcher, mirroring [dev.ipf.darkmatter.media.ByteSizeLruCache].
 */
class BoundedStreamTombstones(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    // accessOrder = true → LinkedHashMap iterates eldest-first for eviction and
    // promotes an entry to MRU on every access, so checking or re-marking a
    // tombstone keeps it alive.
    private val entries =
        object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > maxEntries
        }

    /** Marks [streamId] as removed, promoting it to MRU and evicting the LRU id past the cap. */
    fun add(streamId: String) {
        entries[streamId] = Unit
    }

    /** Drops a single tombstone (the re-add edge case where a stream restarts). */
    fun remove(streamId: String) {
        entries.remove(streamId)
    }

    /**
     * Returns whether [streamId] is currently tombstoned. A positive lookup
     * promotes the id to MRU so an actively-watched stream's tombstone is not
     * evicted out from under it while late events are still arriving.
     */
    operator fun contains(streamId: String): Boolean {
        // Use get(), not containsKey(): only get() triggers the access-order
        // promotion that keeps an actively-checked tombstone off the eviction
        // edge. The value is always Unit, so a non-null result means present.
        return entries[streamId] != null
    }

    fun clear() {
        entries.clear()
    }

    fun size(): Int = entries.size

    companion object {
        /**
         * Cap on retained tombstones. Comfortably larger than the number of
         * agent streams a human keeps live in one open conversation, while
         * keeping worst-case memory and per-batch filter cost flat regardless
         * of how long the session stays open.
         */
        const val DEFAULT_MAX_ENTRIES = 512
    }
}
