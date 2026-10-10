package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteSizeLruCacheTest {
    @Test
    fun emptyCache_getReturnsNull() {
        val cache = ByteSizeLruCache<String, ByteArray>(maxBytes = 100, sizeOf = { it.size })
        assertNull(cache.get("absent"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun putThenGet_roundTrips() {
        val cache = ByteSizeLruCache<String, ByteArray>(maxBytes = 100, sizeOf = { it.size })
        val payload = ByteArray(40)
        cache.put("k", payload)
        assertNotNull(cache.get("k"))
        assertEquals(40L, cache.residentBytes())
    }

    @Test
    fun underCap_noEviction() {
        val cache = ByteSizeLruCache<String, ByteArray>(maxBytes = 100, sizeOf = { it.size })
        cache.put("a", ByteArray(30))
        cache.put("b", ByteArray(30))
        cache.put("c", ByteArray(30))
        assertEquals(3, cache.size())
        assertEquals(90L, cache.residentBytes())
    }

    @Test
    fun evictsPastCap_withoutThrowing() {
        // The exact regression the agent flagged — the previous inline impl
        // threw IllegalStateException at the third put because it called
        // `iterator()` multiple times per eviction step.
        val cache = ByteSizeLruCache<String, ByteArray>(maxBytes = 100, sizeOf = { it.size })
        cache.put("a", ByteArray(60))
        cache.put("b", ByteArray(60)) // forces eviction of "a"
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertEquals(1, cache.size())
        assertEquals(60L, cache.residentBytes())
    }

    @Test
    fun evictsMultipleEntries_inInsertionOrder() {
        // Cap small; insert several big entries; oldest must go first.
        val cache = ByteSizeLruCache<String, ByteArray>(maxBytes = 100, sizeOf = { it.size })
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.put("c", ByteArray(40))
        cache.put("d", ByteArray(40)) // pushes resident to 160 → evict a, b
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
        assertTrue(cache.residentBytes() <= 100)
    }

    @Test
    fun get_promotesEntryToMru_andProtectsItFromEviction() {
        // LRU semantics: accessing "a" makes it the most-recently-used, so
        // a subsequent eviction must remove "b" (now the LRU) instead.
        val cache = ByteSizeLruCache<String, ByteArray>(maxBytes = 100, sizeOf = { it.size })
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.get("a") // promote
        cache.put("c", ByteArray(40)) // resident 120, evict LRU which is now "b"
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun replacingKey_updatesResidentBytesNotAdds() {
        val cache = ByteSizeLruCache<String, ByteArray>(maxBytes = 100, sizeOf = { it.size })
        cache.put("k", ByteArray(40))
        cache.put("k", ByteArray(70)) // replace, not duplicate
        assertEquals(1, cache.size())
        assertEquals(70L, cache.residentBytes())
    }

    @Test
    fun clear_resetsStateAndResidentBytes() {
        val cache = ByteSizeLruCache<String, ByteArray>(maxBytes = 100, sizeOf = { it.size })
        cache.put("a", ByteArray(30))
        cache.put("b", ByteArray(30))
        cache.clear()
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
        assertNull(cache.get("a"))
    }

    @Test
    fun singleValueLargerThanCap_evictsItselfThenIsAbsent() {
        // Edge case: a value bigger than the cap. The loop should evict
        // until resident <= maxBytes, which means evicting the just-inserted
        // entry. Cache ends empty, no exception.
        val cache = ByteSizeLruCache<String, ByteArray>(maxBytes = 50, sizeOf = { it.size })
        cache.put("big", ByteArray(80))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun nonPositiveSizeOf_isClampedSoCapStillBounds() {
        // A sizeOf returning 0/negative would otherwise never count toward the
        // cap; each entry is charged >= 1 byte so eviction still bounds size.
        val cache = ByteSizeLruCache<String, String>(maxBytes = 3, sizeOf = { 0 })
        repeat(10) { cache.put("k$it", "v$it") }
        assertTrue("resident should be bounded by cap", cache.residentBytes() <= 3L)
        assertTrue("size should be bounded by cap", cache.size() <= 3)
    }

    @Test
    fun evictedRemovedAndClearedEntriesInvokeRemovalCallback() {
        val removed = mutableListOf<String>()
        val cache =
            ByteSizeLruCache<String, String>(
                maxBytes = 6,
                sizeOf = { it.length },
                onEntryRemoved = { removed += it },
            )

        cache.put("a", "aaa")
        cache.put("b", "bbb")
        cache.put("c", "ccc")
        cache.remove("b")
        cache.put("d", "ddd")
        cache.clear()

        assertEquals(listOf("aaa", "bbb", "ccc", "ddd"), removed)
    }

    /** Replacement, explicit removal, and clear do not inflate the capacity-eviction metric. */
    @Test
    fun evictionCallbackExcludesReplacementRemovalAndClear() {
        val evicted = mutableListOf<String>()
        val cache =
            ByteSizeLruCache<String, String>(
                maxBytes = 6,
                sizeOf = { it.length },
                onEvicted = { evicted += it },
            )

        cache.put("a", "aaa")
        cache.put("a", "bbb")
        cache.put("c", "ccc")
        cache.remove("a")
        cache.put("d", "ddd")
        cache.clear()
        assertEquals(emptyList<String>(), evicted)

        cache.put("e", "eee")
        cache.put("f", "fff")
        cache.put("g", "ggg")
        assertEquals(listOf("eee"), evicted)
    }

    @Test
    fun sameInstanceReplaceDoesNotInvokeRemovalCallback() {
        val removed = mutableListOf<String>()
        val value = "same"
        val cache =
            ByteSizeLruCache<String, String>(
                maxBytes = 100,
                sizeOf = { it.length },
                onEntryRemoved = { removed += it },
            )

        cache.put("k", value)
        cache.put("k", value)

        assertEquals(value, cache.get("k"))
        assertEquals(4L, cache.residentBytes())
        assertEquals(emptyList<String>(), removed)
    }

    /** An entry larger than the per-entry cap is refused and still replaces the key's previous value. */
    @Test
    fun oversizedEntryIsNotCachedAndRemovesPreviousValue() {
        val removed = mutableListOf<String>()
        val cache =
            ByteSizeLruCache<String, String>(
                maxBytes = 100,
                maxEntryBytes = 10,
                sizeOf = { it.length },
                onEntryRemoved = { removed += it },
            )

        cache.put("k", "small")
        cache.put("k", "this value is too large")

        assertNull(cache.get("k"))
        assertEquals(0L, cache.residentBytes())
        assertEquals(listOf("small"), removed)
    }

    /**
     * An entry that opted out of eviction is skipped and charged nothing, so an evictable entry filling
     * the cap exactly keeps itself, and only older evictable entries make room.
     */
    @Test
    fun nonEvictableEntriesAreSkippedAndChargedNothing() {
        val cache =
            ByteSizeLruCache<String, ByteArray>(
                maxBytes = 100,
                sizeOf = { it.size },
                isEvictable = { it.isNotEmpty() },
            )
        cache.put("file-backed", ByteArray(0))
        cache.put("full", ByteArray(100))
        assertNotNull("an exact fit is not evicted on its own insert", cache.get("full"))
        assertEquals(100L, cache.residentBytes())

        cache.put("newer", ByteArray(60))

        assertNotNull(cache.get("file-backed"))
        assertNull(cache.get("full"))
        assertNotNull(cache.get("newer"))
        assertEquals(60L, cache.residentBytes())
        cache.remove("file-backed")
        assertEquals(60L, cache.residentBytes())
    }
}
