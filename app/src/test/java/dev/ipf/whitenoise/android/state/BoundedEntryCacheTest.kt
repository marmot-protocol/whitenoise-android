package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedEntryCacheTest {
    @Test
    fun keepsCacheBounded() {
        val cache = BoundedEntryCache<String, String>(maxEntries = 2)

        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")

        assertEquals(2, cache.size())
        assertNull(cache["a"])
        assertEquals("B", cache["b"])
        assertEquals("C", cache["c"])
    }

    @Test
    fun lookupPromotesEntryBeforeEviction() {
        val cache = BoundedEntryCache<String, String>(maxEntries = 2)
        cache.put("a", "A")
        cache.put("b", "B")

        assertEquals("A", cache["a"])
        cache.put("c", "C")

        assertEquals("A", cache["a"])
        assertNull(cache["b"])
        assertEquals("C", cache["c"])
    }

    @Test
    fun putReturnsPreviousValue() {
        val cache = BoundedEntryCache<String, String>(maxEntries = 2)

        assertNull(cache.put("a", "A"))
        assertEquals("A", cache.put("a", "AA"))
        assertEquals("AA", cache["a"])
    }

    @Test
    fun rejectsNonPositiveCap() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                BoundedEntryCache<String, String>(maxEntries = 0)
            }

        assertTrue(thrown.message!!.contains("positive"))
    }
}
