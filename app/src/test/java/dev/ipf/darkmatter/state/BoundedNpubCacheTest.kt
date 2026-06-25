package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedNpubCacheTest {
    @Test
    fun keepsCacheBounded() {
        val cache = BoundedNpubCache(maxEntries = 4)

        repeat(100) { i -> cache.put("hex-$i", "npub-$i") }

        assertEquals(4, cache.size())
        assertNull(cache.get("hex-0"))
        assertEquals("npub-99", cache.get("hex-99"))
    }

    @Test
    fun lookupPromotesEntryBeforeEviction() {
        val cache = BoundedNpubCache(maxEntries = 3)
        cache.put("a", "npub-a")
        cache.put("b", "npub-b")
        cache.put("c", "npub-c")

        assertEquals("npub-a", cache.get("a"))
        cache.put("d", "npub-d")

        assertEquals("npub-a", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("npub-c", cache.get("c"))
        assertEquals("npub-d", cache.get("d"))
    }

    @Test
    fun clearDropsEntries() {
        val cache = BoundedNpubCache(maxEntries = 2)
        cache.put("a", "npub-a")
        cache.put("b", "npub-b")

        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }

    @Test
    fun rejectsNonPositiveCap() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                BoundedNpubCache(maxEntries = 0)
            }

        assertTrue(thrown.message!!.contains("positive"))
    }
}
