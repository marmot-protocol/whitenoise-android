package dev.ipf.darkmatter.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiskByteCacheTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("disk-cache-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun emptyCache_getReturnsNull() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        assertNull(cache.get("absent"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun putThenGet_roundTripsThroughDisk() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        val payload = ByteArray(40) { it.toByte() }
        cache.put("k", payload)
        val out = cache.get("k")
        assertNotNull(out)
        assertTrue(out!!.contentEquals(payload))
        assertEquals(40L, cache.residentBytes())
    }

    @Test
    fun emptyPut_ignored() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("k", ByteArray(0))
        assertNull(cache.get("k"))
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun replacingKey_updatesByteAccounting() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("k", ByteArray(40))
        cache.put("k", ByteArray(70))
        assertEquals(1, cache.size())
        assertEquals(70L, cache.residentBytes())
    }

    @Test
    fun pastCap_evictsLRU() {
        // 100-byte cap; three 40-byte entries push over → oldest evicted.
        val cache = DiskByteCache(dir, maxBytes = 100)
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.put("c", ByteArray(40)) // 120 → evict a
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertTrue(cache.residentBytes() <= 100)
    }

    @Test
    fun get_promotesToMRU_protectsFromEviction() {
        val cache = DiskByteCache(dir, maxBytes = 100)
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache.get("a") // bump a to MRU
        cache.put("c", ByteArray(40)) // 120 → evict b (now LRU)
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun clear_deletesAllFiles() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("a", ByteArray(30))
        cache.put("b", ByteArray(30))
        cache.clear()
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
        assertNull(cache.get("a"))
        // Directory remains but empty (orphan-sweep happens in clear).
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun reinit_rehydratesIndexFromDisk() {
        // The whole point of L2: process restart rehydrates the cache.
        DiskByteCache(dir, maxBytes = 1024).run {
            put("a", ByteArray(40) { 1 })
            put("b", ByteArray(50) { 2 })
        }
        // Simulate process restart by constructing a new instance.
        val rehydrated = DiskByteCache(dir, maxBytes = 1024)
        assertEquals(2, rehydrated.size())
        assertEquals(90L, rehydrated.residentBytes())
        val a = rehydrated.get("a")
        assertNotNull(a)
        assertTrue(a!!.all { it == 1.toByte() })
        val b = rehydrated.get("b")
        assertNotNull(b)
        assertTrue(b!!.all { it == 2.toByte() })
    }

    @Test
    fun rehydration_isDeferredUntilFirstAccess() {
        // #100: the constructor must not do directory I/O (it ran on the main
        // thread at app launch). Proof: build the cache over an empty dir, then
        // have a *separate* instance write an entry to the same dir. If the
        // first instance only rehydrates on first access it scans the dir now
        // and sees the entry; eager constructor rehydration would have missed
        // it (the dir was empty at construction time).
        val cache = DiskByteCache(dir, maxBytes = 1024)
        DiskByteCache(dir, maxBytes = 1024).put("late", ByteArray(40) { 9 })

        val out = cache.get("late")
        assertNotNull(out)
        assertTrue(out!!.all { it == 9.toByte() })
    }

    @Test
    fun reinit_evictsToFitReducedCap() {
        DiskByteCache(dir, maxBytes = 1024).run {
            put("a", ByteArray(40))
            put("b", ByteArray(40))
            put("c", ByteArray(40))
        }
        // Restart with tighter cap — should trim down on init.
        val tighter = DiskByteCache(dir, maxBytes = 50)
        assertTrue(tighter.residentBytes() <= 50)
        assertTrue(tighter.size() <= 1)
    }

    @Test
    fun missingFileOnDisk_evictsIndexEntryReturnsNull() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("k", ByteArray(40))
        // Tamper: delete the file out from under the cache.
        dir.listFiles()?.forEach { it.delete() }
        assertNull(cache.get("k"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun clear_skipsForeignFilesInDir() {
        // Defensive: if a future co-tenant ever drops files in cacheDir,
        // clear() must not wipe them. Only our own `.bin` / `.tmp` files.
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("a", ByteArray(30))
        val foreign = File(dir, "not-mine.txt").also { it.writeText("hello") }
        cache.clear()
        // Our entry is gone…
        assertNull(cache.get("a"))
        assertEquals(0, cache.size())
        // …but the foreign file survives.
        assertTrue("foreign file should survive", foreign.exists())
        assertEquals("hello", foreign.readText())
    }

    @Test
    fun put_writesAtomically_noPartialFile() {
        // Sanity check that the .tmp → rename dance leaves no `.tmp` files
        // behind on successful writes.
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("k", ByteArray(40))
        val tmpFiles = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("no .tmp file should linger after successful put", tmpFiles.isEmpty())
    }

    @Test
    fun differentKeys_collideToDifferentFiles() {
        // Defense against hash collision oversight — two keys must map to
        // two distinct files. (sha256 makes real collisions improbable; this
        // pins that we're hashing the key, not the file content.)
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("alice|group|msg-1", ByteArray(20))
        cache.put("bob|group|msg-1", ByteArray(30))
        assertEquals(2, cache.size())
        assertEquals(50L, cache.residentBytes())
    }
}
