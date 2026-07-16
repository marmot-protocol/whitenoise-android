package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedNpubCacheTest {
    @Test
    fun keepsCacheBounded() {
        val cache = BoundedNpubCache(ScopedCacheRegistry(), maxEntries = 4)

        repeat(100) { i -> cache.put("hex-$i", "npub-$i") }

        assertEquals(4, cache.size())
        assertNull(cache.get("hex-0"))
        assertEquals("npub-99", cache.get("hex-99"))
    }

    @Test
    fun lookupPromotesEntryBeforeEviction() {
        val cache = BoundedNpubCache(ScopedCacheRegistry(), maxEntries = 3)
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
        val cache = BoundedNpubCache(ScopedCacheRegistry(), maxEntries = 2)
        cache.put("a", "npub-a")
        cache.put("b", "npub-b")

        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }

    @Test
    fun registryClearDropsEntries() {
        val registry = ScopedCacheRegistry()
        val cache = BoundedNpubCache(registry, maxEntries = 2)
        cache.put("a", "npub-a")
        cache.put("b", "npub-b")

        registry.clearAll()

        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(0, cache.size())
    }

    @Test
    fun rejectsNonPositiveCap() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                BoundedNpubCache(ScopedCacheRegistry(), maxEntries = 0)
            }

        assertTrue(thrown.message!!.contains("positive"))
    }
}
