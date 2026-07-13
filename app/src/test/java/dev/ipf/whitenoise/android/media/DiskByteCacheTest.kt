package dev.ipf.whitenoise.android.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun oversizedEntry_isNotPersistedOrReadBack() {
        val cache = DiskByteCache(dir, maxBytes = 1024, maxEntryBytes = 64)
        cache.put("too-large", ByteArray(65))

        assertNull(cache.get("too-large"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".bin") } ?: true)
    }

    @Test
    fun rehydrateDropsOversizedEntryBeforeReadBytes() {
        val writer = DiskByteCache(dir, maxBytes = 1024, maxEntryBytes = 128)
        writer.put("large", ByteArray(120) { 1 })

        val tighter = DiskByteCache(dir, maxBytes = 1024, maxEntryBytes = 64)
        assertNull(tighter.get("large"))
        assertEquals(0L, tighter.residentBytes())
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".bin") } ?: true)
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
    fun replacingKey_keepsTheNewBytesReadableAndOnDisk() {
        // Regression: the replace path scheduled the previous entry's file for
        // deletion after the monitor, but same-key entries share the destination
        // path, so the deferred delete removed the freshly-renamed bytes.
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("k", ByteArray(40) { 1 })
        val updated = ByteArray(70) { 2 }
        cache.put("k", updated)

        val out = cache.get("k")
        assertNotNull("replaced value must still be readable", out)
        assertTrue(out!!.contentEquals(updated))
        assertTrue("a `.bin` must remain on disk after replace", dir.listFiles()?.any { it.name.endsWith(".bin") } ?: false)

        // Survives a restart: a fresh cache over the same dir rehydrates it.
        val reopened = DiskByteCache(dir, maxBytes = 1024)
        val afterRestart = reopened.get("k")
        assertNotNull("replaced value must survive restart", afterRestart)
        assertTrue(afterRestart!!.contentEquals(updated))
    }

    @Test
    fun replacingKeyDefersIndexRemovalUntilAfterRenamesSucceed() {
        val source =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/media/DiskByteCache.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/media/DiskByteCache.kt"),
            ).firstOrNull { it.exists() }?.readText()
                ?: error("Missing DiskByteCache.kt source file")
        val body = source.kotlinFunctionBody("put")

        assertTrue(
            "same-key replacement must inspect the existing entry before renames but remove it only after the data commit succeeds",
            body.indexOf("val existing = existingSnapshot") < body.indexOf("!renameFile(tmp, file)") &&
                body.indexOf("!renameFile(tmp, file)") < body.lastIndexOf("index.remove(hashed)") &&
                body.lastIndexOf("index.remove(hashed)") < body.indexOf("index[hashed] = Entry(file, size, ciphertextTag)"),
        )
    }

    @Test
    fun failedReplacementKeepsOldBytesAndRestoresTheirTagAcrossRestart() {
        var failDataCommit = false
        val cache =
            DiskByteCache(
                cacheDir = dir,
                maxBytes = 1024,
                renameFile = { source, target ->
                    if (failDataCommit && source.name.contains("-bin-") && target.name.endsWith(".bin")) {
                        false
                    } else {
                        source.renameTo(target)
                    }
                },
            )
        val original = ByteArray(40) { 1 }
        cache.put("k", original, cache.generation(), ciphertextTag = "old-ciphertext")

        failDataCommit = true
        cache.put("k", ByteArray(40) { 2 }, cache.generation(), ciphertextTag = "new-ciphertext")

        assertTrue(cache.get("k")!!.contentEquals(original))
        val reopened = DiskByteCache(dir, maxBytes = 1024)
        assertTrue(reopened.get("k")!!.contentEquals(original))
        assertEquals(0, reopened.removeByCiphertextTags(setOf("new-ciphertext")))
        assertEquals(1, reopened.removeByCiphertextTags(setOf("old-ciphertext")))
        assertNull(reopened.get("k"))
    }

    @Test
    fun failedUntaggedReplacementKeepsTaggedExistingEntryAndTag() {
        // Regression: an untagged same-key replacement whose `.bin` rename fails
        // never touches the tag file, so the still-valid tagged existing entry
        // must survive — the fail-closed cleanup only applies to puts that
        // attempted a tag rename (tagTmp != null).
        var failDataCommit = false
        val cache =
            DiskByteCache(
                cacheDir = dir,
                maxBytes = 1024,
                renameFile = { source, target ->
                    if (failDataCommit && source.name.contains("-bin-") && target.name.endsWith(".bin")) {
                        false
                    } else {
                        source.renameTo(target)
                    }
                },
            )
        val original = ByteArray(40) { 1 }
        cache.put("k", original, cache.generation(), ciphertextTag = "old-ciphertext")

        failDataCommit = true
        cache.put("k", ByteArray(40) { 2 }, cache.generation(), ciphertextTag = null)

        assertTrue("old bytes must survive a failed untagged replacement", cache.get("k")!!.contentEquals(original))
        val reopened = DiskByteCache(dir, maxBytes = 1024)
        assertTrue("old bytes must survive restart", reopened.get("k")!!.contentEquals(original))
        // The old tag must still authorize eviction — proof the sidecar was untouched.
        assertEquals(1, reopened.removeByCiphertextTags(setOf("old-ciphertext")))
        assertNull(reopened.get("k"))
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
    fun clearBeforeHydrationSweepsOwnedFilesWithoutIndexScan() {
        DiskByteCache(dir, maxBytes = 1024).put("a", ByteArray(30))
        val fresh = DiskByteCache(dir, maxBytes = 1024)

        fresh.clear()

        assertEquals(0, fresh.size())
        assertEquals(0L, fresh.residentBytes())
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
    fun contains_beforeHydration_reportsMissWithoutStattingDisk() {
        // #983: contains() is reached from composition, so the un-hydrated
        // branch must not stat the backing file — it reports a plain miss and
        // leaves hydration to the first real get/put on Dispatchers.IO. Proof:
        // an entry persisted by a previous "session" is invisible to a fresh
        // instance's contains() until something hydrates the index.
        DiskByteCache(dir, maxBytes = 1024).put("persisted", ByteArray(40) { 7 })

        val cold = DiskByteCache(dir, maxBytes = 1024)
        assertFalse(cold.contains("persisted"))

        // First real read hydrates; the probe now sees the on-disk entry.
        assertNotNull(cold.get("persisted"))
        assertTrue(cold.contains("persisted"))
    }

    @Test
    fun contains_afterHydration_reflectsIndexWithoutSeedingIt() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put("present", ByteArray(40) { 1 })

        assertTrue(cache.contains("present"))
        assertFalse(cache.contains("absent"))
        // Strictly a peek: probing an absent key must not create an entry.
        assertEquals(1, cache.size())
    }

    @Test
    fun put_overloadsAreNotSynchronizedMethods() {
        val deferredPut =
            DiskByteCache::class.java.getDeclaredMethod(
                "put",
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType!!,
                String::class.java,
            )
        val immediatePut =
            DiskByteCache::class.java.getDeclaredMethod(
                "put",
                String::class.java,
                ByteArray::class.java,
            )

        assertFalse(
            "deferred put must not hold the object monitor across disk writes",
            Modifier.isSynchronized(deferredPut.modifiers),
        )
        assertFalse(
            "immediate put must not hold the object monitor across disk writes",
            Modifier.isSynchronized(immediatePut.modifiers),
        )
    }

    @Test
    fun put_coldHydrationDoesNotBlockContainsMonitor() {
        DiskByteCache(dir, maxBytes = 1024).put("persisted", ByteArray(40) { 3 })

        val hydrationEntered = CountDownLatch(1)
        val releaseHydration = CountDownLatch(1)
        val blockingDir = BlockingListFilesDir(dir, hydrationEntered, releaseHydration)
        val cache = DiskByteCache(blockingDir, maxBytes = 1024)
        val putFinished = CountDownLatch(1)
        val putThread =
            Thread {
                try {
                    cache.put("new", ByteArray(40) { 4 }, cache.generation())
                } finally {
                    putFinished.countDown()
                }
            }
        putThread.start()

        assertTrue(
            "put should enter cold hydration",
            hydrationEntered.await(5, TimeUnit.SECONDS),
        )
        assertFalse(
            "put should still be parked in cold hydration",
            putFinished.await(100, TimeUnit.MILLISECONDS),
        )

        val containsFinished = CountDownLatch(1)
        var containsValue: Boolean? = null
        val containsThread =
            Thread {
                try {
                    containsValue = cache.contains("persisted")
                } finally {
                    containsFinished.countDown()
                }
            }
        containsThread.start()

        try {
            assertTrue(
                "contains must not wait for cold put hydration to release the cache monitor",
                containsFinished.await(500, TimeUnit.MILLISECONDS),
            )
            assertFalse(
                "cold contains still reports miss before hydration is installed",
                containsValue!!,
            )
        } finally {
            releaseHydration.countDown()
            putThread.join(5_000)
            containsThread.join(5_000)
        }
        assertTrue("put should finish after hydration is released", putFinished.await(5, TimeUnit.SECONDS))
        assertNotNull(cache.get("new"))
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
    fun get_transientReadError_keepsIndexedEntrySubjectToByteCap() {
        val cache = DiskByteCache(dir, maxBytes = 100)
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        val unreadable = File(dir, sha256Hex("a") + ".bin")
        assertTrue(unreadable.setReadable(false, false))

        assertNull("transient read failure is a miss", cache.get("a"))
        assertTrue("backing path must still exist", unreadable.isFile)
        assertTrue(cache.contains("a"))
        assertEquals(2, cache.size())
        assertEquals(80L, cache.residentBytes())

        cache.put("c", ByteArray(40))
        assertTrue(cache.residentBytes() <= 100)
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))

        unreadable.setReadable(true, false)
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
    fun tempFileNamesUseAProcessCounterForSameKeyWrites() {
        val cache = DiskByteCache(dir, maxBytes = 1024)
        val method =
            DiskByteCache::class.java
                .getDeclaredMethod("uniqueTmpFile", String::class.java, String::class.java)
                .apply { isAccessible = true }

        val names =
            (0 until 256).map {
                (method.invoke(cache, "same-hash", "bin") as File).name
            }

        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("same-hash-bin-") && it.endsWith(".tmp") })
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
    fun taggedPut_failsClosed_whenTagCannotBePersisted() {
        // The ciphertext tag authorizes hash-based expiry deletion, so a tagged
        // write must fail closed: if the tag can't land, no decrypted .bin may
        // survive untagged. Force the failure by occupying the tag's final path
        // with a non-empty directory (renameTo onto it fails).
        val key = "acct|grp|msg|0"
        File(dir, sha256Hex(key) + ".tag").apply {
            mkdirs()
            File(this, "occupied").writeText("x")
        }
        val cache = DiskByteCache(dir, maxBytes = 1024)
        cache.put(key, ByteArray(40) { 1 }, cache.generation(), "the-hash")
        assertNull("a tagged write whose tag failed must not be readable", cache.get(key))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.residentBytes())
        assertTrue(
            "no decrypted .bin may linger when its required tag could not be written",
            dir.listFiles()?.none { it.isFile && it.name.endsWith(".bin") } ?: true,
        )
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

    private fun sha256Hex(value: String): String {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun String.kotlinFunctionBody(functionName: String): String {
        val start =
            Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
                .find(this)
                ?.range
                ?.first
                ?: error("Missing function $functionName")
        val braceStart = indexOf('{', start)
        require(braceStart >= 0) { "Missing body for $functionName" }
        var depth = 0
        var index = braceStart
        while (index < length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    index += 1
                    if (depth == 0) return substring(braceStart, index)
                    continue
                }
            }
            index += 1
        }
        error("Unterminated function $functionName")
    }

    private class BlockingListFilesDir(
        private val delegate: File,
        private val hydrationEntered: CountDownLatch,
        private val releaseHydration: CountDownLatch,
    ) : File(delegate.path) {
        override fun mkdirs(): Boolean = delegate.mkdirs()

        override fun listFiles(): Array<File>? {
            hydrationEntered.countDown()
            try {
                releaseHydration.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return delegate.listFiles()
        }
    }
}
