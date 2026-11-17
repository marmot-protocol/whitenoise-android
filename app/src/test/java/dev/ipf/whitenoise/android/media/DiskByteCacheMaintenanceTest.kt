package dev.ipf.whitenoise.android.media

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

/** Cache inventory reads and conditional rejection preserve ordinary LRU and replacement behavior. */
class DiskByteCacheMaintenanceTest {
    private val directory = Files.createTempDirectory("cache-maintenance-test").toFile()
    private val key = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") }

    /** Removes only this test's private cache directory. */
    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** An actual read during inventory traversal remains newer than untouched inventory entries. */
    @Test
    fun inventoryDoesNotPromoteEntriesOverAnInterleavedRealRead() {
        val sizing = DiskByteCache(directory, 1024L * 1024, key)
        val payload = ByteArray(32) { 1 }
        sizing.put("a", payload)
        val entryBytes = sizing.residentBytes()
        sizing.clear()
        val cache = DiskByteCache(directory, entryBytes * 3, key)
        listOf("a", "b", "c").forEach { cache.put(it, payload) }
        var first = true
        cache.forEachStoredEntry { _, _ ->
            if (first) {
                first = false
                assertNotNull(cache.get("a"))
            }
        }
        cache.put("d", payload)
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
    }

    /** A failed decode of an old payload cannot discard a newer same-slot publication. */
    @Test
    fun conditionalRejectionPreservesAReplacement() {
        val cache = DiskByteCache(directory, 1024L * 1024, key)
        val before = byteArrayOf(1, 2)
        val after = byteArrayOf(3, 4)
        cache.put("slot", before)
        cache.put("slot", after)
        cache.discardIfMatches("slot", before)
        assertArrayEquals(after, cache.get("slot"))
        cache.discardIfMatches("slot", after)
        assertNull(cache.get("slot"))
    }
}
