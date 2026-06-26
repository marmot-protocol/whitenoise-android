package dev.ipf.whitenoise.android.media

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
    fun put_withStaleGeneration_isRejectedAfterClear() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        // Capture the generation a deferred write would have grabbed at
        // schedule time, then sign-out wipes the cache before it lands.
        val scheduledGeneration = cache.generation()
        cache.clear()
        cache.put("k", ByteArray(40) { 7 }, scheduledGeneration)
        assertNull("a write from a wiped session must not re-persist", cache.get("k"))
        assertEquals(0L, cache.residentBytes())
    }

    @Test
    fun put_withCurrentGeneration_succeedsAfterClear() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.clear()
        // A write scheduled after the wipe (current generation) is honored.
        cache.put("k", ByteArray(40) { 7 }, cache.generation())
        assertNotNull(cache.get("k"))
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
    fun get_refreshesFileLastModifiedForReadRecency() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("a", ByteArray(40))
        val file = dir.listFiles { f -> f.isFile }!!.single()
        file.setLastModified(1_000L)
        cache.get("a")
        // Post-restart rehydration orders by lastModified, so a read must
        // refresh it or read-hot entries get evicted first. See #228.
        assertTrue(file.lastModified() > 1_000L)
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
    fun removeByCiphertextTags_evictsTaggedEntriesOnly() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("acct|grp|msg-1|0", ByteArray(30), cache.generation(), "hash-a")
        cache.put("acct|grp|msg-2|0", ByteArray(30), cache.generation(), "hash-b")
        cache.put("acct|grp|msg-3|0", ByteArray(30)) // untagged
        val removed = cache.removeByCiphertextTags(setOf("hash-a"))
        assertEquals(1, removed)
        assertNull(cache.get("acct|grp|msg-1|0"))
        assertNotNull(cache.get("acct|grp|msg-2|0"))
        assertNotNull(cache.get("acct|grp|msg-3|0"))
        assertEquals(60L, cache.residentBytes())
    }

    @Test
    fun removeByCiphertextTags_worksAfterRehydrate_forUnloadedMedia() {
        // The #334 crux: media cached in a prior session must still be evictable
        // by ciphertext hash after a process restart, when nothing in memory maps
        // the hash to its cache key. Proven by tagging, dropping the instance, and
        // evicting purely by hash from a fresh instance over the same dir.
        // generation 0 is the initial generation of a fresh instance.
        DiskByteCache(dir, maxBytes = 1024)
            .put("acct|grp|old-msg|0", ByteArray(40) { 5 }, 0, "expired-hash")
        val rehydrated = DiskByteCache(dir, maxBytes = 1024)
        assertNotNull("entry should survive the restart", rehydrated.get("acct|grp|old-msg|0"))
        val removed = rehydrated.removeByCiphertextTags(setOf("expired-hash"))
        assertEquals("the persisted tag must drive eviction across sessions", 1, removed)
        assertNull(rehydrated.get("acct|grp|old-msg|0"))
        assertEquals(0L, rehydrated.residentBytes())
        // The sidecar must be gone too, not orphaned.
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".tag") } ?: true)
    }

    @Test
    fun removeByCiphertextTags_emptySet_isNoOp() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("k", ByteArray(30), cache.generation(), "h")
        assertEquals(0, cache.removeByCiphertextTags(emptySet()))
        assertNotNull(cache.get("k"))
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
