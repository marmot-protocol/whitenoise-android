package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedCacheTest {
    @Test
    fun keepsEntriesBoundedAndPromotesReads() {
        val registry = ScopedCacheRegistry()
        val cache = ScopedCache<String, String>(registry, name = "profiles", maxEntries = 2)
        cache.put("a", "A")
        cache.put("b", "B")

        assertEquals("A", cache["a"])
        cache.put("c", "C")

        assertEquals(2, cache.size)
        assertEquals("A", cache["a"])
        assertNull(cache["b"])
        assertEquals("C", cache["c"])
    }

    @Test
    fun clearAllDropsEveryCacheRegisteredAtConstruction() {
        val registry = ScopedCacheRegistry()
        val collapseLongMessages = ScopedCache<String, Boolean>(registry, name = "collapse-long-messages", maxEntries = 4)
        val hiddenMessages = ScopedCache<String, Set<String>>(registry, name = "hidden-messages", maxEntries = 4)
        collapseLongMessages.put("alice:group", false)
        hiddenMessages.put("alice:group", setOf("message"))

        registry.clearAll()

        assertNull(collapseLongMessages["alice:group"])
        assertNull(hiddenMessages["alice:group"])
    }

    @Test
    fun scopedSetRegistersWithRegistryAndStaysBounded() {
        val registry = ScopedCacheRegistry()
        val set = ScopedSet<String>(registry, name = "materializing-profiles", maxEntries = 2)

        assertTrue(set.add("a"))
        assertTrue(set.add("b"))
        assertFalse(set.add("b"))
        assertTrue(set.add("c"))

        assertEquals(2, set.size)
        assertFalse("oldest entry should be evicted", "a" in set)
        assertTrue("recent entry should remain", "b" in set)
        assertTrue("new entry should remain", "c" in set)

        registry.clearAll()

        assertEquals(0, set.size)
    }
}
