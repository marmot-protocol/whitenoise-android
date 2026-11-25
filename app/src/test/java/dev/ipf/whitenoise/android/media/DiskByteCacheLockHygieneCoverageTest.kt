package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
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
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import javax.crypto.spec.SecretKeySpec

/**
 * Guards [DiskByteCache]'s two-lock contract: main-thread [DiskByteCache.contains] probes
 * acquire only the pure-memory index monitor (`this`) and never block on directory fsync, rename,
 * temp-file creation, stale-entry unlink, poisoned-entry unlink, or `clear()` directory sweeps.
 */
class DiskByteCacheLockHygieneCoverageTest {
    private lateinit var dir: File
    private val keyProvider =
        DiskByteCacheKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }

    /** Creates an isolated temporary cache directory for each test. */
    @Before
    fun setUp() {
        dir = Files.createTempDirectory("disk-cache-lock-hygiene-test").toFile()
    }

    /** Deletes the temporary cache directory after each test. */
    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Verifies no `synchronized(this)` block or `@Synchronized` method holds disk I/O or publicationLock. */
    @Test
    fun indexMonitorNeverHoldsDiskIoOrPublicationLock() {
        val source = appModuleSource(DISK_BYTE_CACHE_SOURCE)
        val indexBlocks = synchronizedThisBlocks(source)
        assertTrue("expected DiskByteCache to guard its index with synchronized(this)", indexBlocks.isNotEmpty())
        indexBlocks.forEach { block ->
            FORBIDDEN_INDEX_MONITOR_TOKENS.forEach { token ->
                assertFalse(
                    "DiskByteCache synchronized(this) must not invoke $token:\n$block",
                    block.contains(token),
                )
            }
        }

        val codeOnly = stripComments(source)
        val synchronizedAnnotations = Regex("""@Synchronized\b""").findAll(codeOnly).toList()
        assertEquals(
            "only capturePublicationToken should use @Synchronized on DiskByteCache",
            1,
            synchronizedAnnotations.size,
        )
        val annotationSlice =
            codeOnly.substring(synchronizedAnnotations.single().range.first).substringBefore("\n\n")
        assertTrue(
            "@Synchronized must remain restricted to capturePublicationToken",
            annotationSlice.contains("fun capturePublicationToken("),
        )
        val putBody = stripComments(source.functionBody("put"))
        assertFalse("put() must compute envelope size without calling file.length()", putBody.contains(".length("))

        val clearMethods = DiskByteCache::class.java.declaredMethods.filter { it.name == "clear" }
        assertTrue("expected clear() method on DiskByteCache", clearMethods.isNotEmpty())
        assertTrue(
            "clear() must serialize filesystem wipes on publicationLock, not the object monitor",
            clearMethods.none { Modifier.isSynchronized(it.modifiers) },
        )
    }

    /** Verifies `contains()` returns immediately while `put()` is blocked in `syncCacheDirectory`. */
    @Test
    fun containsDoesNotBlockWhilePutSyncsCacheDirectory() {
        val syncEntered = CountDownLatch(1)
        val releaseSync = CountDownLatch(1)
        val blockSync = AtomicBoolean(false)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                syncCacheDirectory = { directory ->
                    if (blockSync.get()) {
                        syncEntered.countDown()
                        releaseSync.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                    }
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                },
            )
        cache.put("existing", ByteArray(32) { 1 })
        blockSync.set(true)

        val incomingPayload = ByteArray(32) { 2 }
        val putThread = Thread { cache.put("incoming", incomingPayload) }
        putThread.start()
        try {
            assertTrue("put should enter syncCacheDirectory", syncEntered.await(BARRIER_SECONDS, TimeUnit.SECONDS))
            assertTrue("contains(existing) must not block on directory fsync", probeContains(cache, "existing"))
            assertFalse("contains(incoming) must not block on directory fsync", probeContains(cache, "incoming"))
        } finally {
            releaseSync.countDown()
            putThread.join(JOIN_TIMEOUT_MS)
        }

        assertTrue(cache.contains("incoming"))
        assertTrue(cache.get("incoming")!!.contentEquals(incomingPayload))
    }

    /** Verifies `contains()` returns immediately while `put()` is blocked in `renameFile`. */
    @Test
    fun containsDoesNotBlockWhilePutRenamesTempFile() {
        val renameEntered = CountDownLatch(1)
        val releaseRename = CountDownLatch(1)
        val blockRename = AtomicBoolean(false)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                renameFile = { source, target ->
                    if (blockRename.get()) {
                        renameEntered.countDown()
                        releaseRename.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                    }
                    source.renameTo(target)
                },
            )
        cache.put("existing", ByteArray(32) { 1 })
        blockRename.set(true)

        val incomingPayload = ByteArray(32) { 3 }
        val putThread = Thread { cache.put("incoming", incomingPayload) }
        putThread.start()
        try {
            assertTrue("put should enter renameFile", renameEntered.await(BARRIER_SECONDS, TimeUnit.SECONDS))
            assertTrue("contains(existing) must not block on rename", probeContains(cache, "existing"))
            assertFalse("contains(incoming) must not block on rename", probeContains(cache, "incoming"))
        } finally {
            releaseRename.countDown()
            putThread.join(JOIN_TIMEOUT_MS)
        }

        assertTrue(cache.contains("incoming"))
        assertTrue(cache.get("incoming")!!.contentEquals(incomingPayload))
    }

    /** Verifies `contains()` returns immediately while `put()` is creating its temp file directory. */
    @Test
    fun containsDoesNotBlockWhilePutCreatesTempFile() {
        val mkdirsEntered = CountDownLatch(1)
        val releaseMkdirs = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val blockingDir = BlockingMkdirsDir(dir, armed, mkdirsEntered, releaseMkdirs)
        val cache = DiskByteCache(cacheDir = blockingDir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("existing", ByteArray(32) { 1 })
        armed.set(true)

        val putThread = Thread { cache.put("incoming", ByteArray(32) { 4 }) }
        putThread.start()
        try {
            assertTrue("put should enter cacheDir.mkdirs()", mkdirsEntered.await(BARRIER_SECONDS, TimeUnit.SECONDS))
            assertTrue("contains(existing) must not block on temp-file setup", probeContains(cache, "existing"))
            assertFalse("contains(incoming) must not block on temp-file setup", probeContains(cache, "incoming"))
        } finally {
            releaseMkdirs.countDown()
            putThread.join(JOIN_TIMEOUT_MS)
        }

        assertTrue(cache.contains("incoming"))
    }

    /** Verifies `contains()` returns immediately while `clear()` sweeps files off disk. */
    @Test
    fun containsDoesNotBlockWhileClearSweepsDisk() {
        val listEntered = CountDownLatch(1)
        val releaseList = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val blockingDir = BlockingListDir(dir, armed, listEntered, releaseList)
        val cache = DiskByteCache(cacheDir = blockingDir, keyProvider = keyProvider, maxBytes = 1024)
        cache.put("existing", ByteArray(32) { 7 })
        armed.set(true)

        val clearThread = Thread { cache.clear() }
        clearThread.start()
        try {
            assertTrue("clear should enter directory listing", listEntered.await(BARRIER_SECONDS, TimeUnit.SECONDS))
            assertFalse("contains must not block while clear sweeps disk", probeContains(cache, "existing"))
        } finally {
            releaseList.countDown()
            clearThread.join(JOIN_TIMEOUT_MS)
        }

        assertEquals(0, cache.size())
        assertTrue(dir.listFiles()?.none { it.isFile && it.name.endsWith(".enc") } ?: true)
    }

    /** Verifies expiry sweeps serialize behind an in-flight `put` sync and still remove the entry. */
    @Test
    fun expirySweepWaitsForInFlightPutSyncAndRemovesJustPublishedEntry() {
        val syncEntered = CountDownLatch(1)
        val releaseSync = CountDownLatch(1)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024,
                syncCacheDirectory = { directory ->
                    syncEntered.countDown()
                    releaseSync.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                },
            )
        cache.prepare()
        val putThread =
            Thread {
                cache.put("k", ByteArray(40) { 5 }, cache.capturePublicationToken(), "exp-tag")
            }
        val sweepStarted = CountDownLatch(1)
        val removed = AtomicInteger(-1)
        val sweepThread =
            Thread {
                sweepStarted.countDown()
                removed.set(cache.removeByCiphertextTags(setOf("exp-tag")))
            }
        putThread.start()
        try {
            assertTrue("put should enter syncCacheDirectory", syncEntered.await(BARRIER_SECONDS, TimeUnit.SECONDS))
            sweepThread.start()
            assertTrue("expiry sweep thread should start", sweepStarted.await(BARRIER_SECONDS, TimeUnit.SECONDS))
            assertTrue(
                "expiry sweep should block on publicationLock while put syncs",
                awaitThreadState(sweepThread, Thread.State.BLOCKED),
            )
            assertFalse("contains must stay non-blocking while put and sweep contend", probeContains(cache, "k"))
        } finally {
            releaseSync.countDown()
            putThread.join(JOIN_TIMEOUT_MS)
            sweepThread.join(JOIN_TIMEOUT_MS)
        }

        assertEquals("expiry sweep must remove the entry published during its wait", 1, removed.get())
        assertFalse(cache.contains("k"))
        assertNull(cache.get("k"))
        assertTrue(dir.listFiles()?.none { it.isFile && it.name.endsWith(".enc") } ?: true)
    }

    /** Verifies `getIfSmall` rejects a concurrent chunked replacement during `put` sync without poisoning it. */
    @Test
    fun getIfSmallRejectsConcurrentChunkedReplacementDuringPutSync() =
        assertGetIfSmallRejectsConcurrentReplacement(
            replacementPayload = ByteArray(1024 * 1024 + 37) { 9 },
            smallMaxBytes = 1024L,
        )

    /** Verifies `getIfSmall` rejects an oversized legacy replacement during `put` sync without poisoning it. */
    @Test
    fun getIfSmallRejectsConcurrentLegacyReplacementDuringPutSync() =
        assertGetIfSmallRejectsConcurrentReplacement(
            replacementPayload = ByteArray(4096) { 8 },
            smallMaxBytes = 1024L,
        )

    /** Verifies `get` and `materialize` never expose an unpublished same-key replacement while sync stalls. */
    @Test
    fun getAndMaterializeHideUnpublishedSameKeyReplacementWhileSyncStalls() {
        val replacement = ByteArray(128) { 2 }
        val cache =
            runPausedSameKeyReplacement(
                maxBytes = 1024L,
                initialPayload = ByteArray(32) { 1 },
                replacementPayload = replacement,
                pauseAfterRename = false,
            )
        assertEquals(replacement.size.toLong(), cache.plaintextSizeOf("k"))
        assertTrue(cache.get("k")!!.contentEquals(replacement))
        cache.materialize("k").use { lease ->
            assertNotNull(lease)
            assertTrue(lease!!.file.readBytes().contentEquals(replacement))
        }
    }

    /** Verifies `get` and `materialize` never expose a same-key replacement the writer immediately evicts. */
    @Test
    fun getAndMaterializeHideImmediatelyEvictedSameKeyReplacementWhileSyncStalls() {
        val cache =
            runPausedSameKeyReplacement(
                maxBytes = 100L,
                initialPayload = ByteArray(20) { 1 },
                replacementPayload = ByteArray(50) { 2 },
                pauseAfterRename = false,
            )
        assertFalse(cache.contains("k"))
        assertNull(cache.get("k"))
        assertNull(cache.materialize("k"))
        assertEquals(0L, cache.residentBytes())
        assertTrue(dir.listFiles()?.none { it.isFile && it.name.endsWith(".enc") } ?: true)
    }

    /** Verifies `get` and `materialize` reject a renamed generation while the old entry is still indexed. */
    @Test
    fun getAndMaterializeRejectRenamedGenerationBeforeOldIndexEntryIsDetached() {
        val replacement = ByteArray(128) { 2 }
        val cache =
            runPausedSameKeyReplacement(
                maxBytes = 1024L,
                initialPayload = ByteArray(32) { 1 },
                replacementPayload = replacement,
                pauseAfterRename = true,
            )
        assertEquals(replacement.size.toLong(), cache.plaintextSizeOf("k"))
        assertTrue(cache.get("k")!!.contentEquals(replacement))
        cache.materialize("k").use { lease ->
            assertNotNull(lease)
            assertTrue(lease!!.file.readBytes().contentEquals(replacement))
        }
    }

    /** Verifies computed envelope byte accounting matches on-disk file lengths for legacy and chunked entries. */
    @Test
    fun computedEnvelopeLengthMatchesDiskForLegacyAndChunkedEntries() {
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 4L * 1024L * 1024L,
                maxEntryBytes = 2L * 1024L * 1024L,
                availablePlaintextAllocationBytes = { Long.MAX_VALUE },
            )
        cache.put("small", ByteArray(40) { 1 }, cache.capturePublicationToken(), "small-tag")
        cache.put("large", ByteArray(1024 * 1024 + 37) { 2 }, cache.capturePublicationToken(), "large-tag")

        val diskTotalBytes =
            dir
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".enc") }
                .sumOf(File::length)
        val reopened =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 4L * 1024L * 1024L,
                maxEntryBytes = 2L * 1024L * 1024L,
                availablePlaintextAllocationBytes = { Long.MAX_VALUE },
            )

        assertEquals(diskTotalBytes, cache.residentBytes())
        assertEquals(diskTotalBytes, reopened.residentBytes())
    }

    /** Pauses a same-key replacement in `syncCacheDirectory` and checks `getIfSmall` rejects without poisoning. */
    private fun assertGetIfSmallRejectsConcurrentReplacement(
        replacementPayload: ByteArray,
        smallMaxBytes: Long,
    ) {
        val syncEntered = CountDownLatch(1)
        val releaseSync = CountDownLatch(1)
        val blockSync = AtomicBoolean(false)
        val oversizedAllocations = AtomicInteger(0)
        val inSmallRead = AtomicBoolean(false)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 4L * 1024L * 1024L,
                maxEntryBytes = 2L * 1024L * 1024L,
                availablePlaintextAllocationBytes = { Long.MAX_VALUE },
                plaintextAllocator = { size ->
                    if (inSmallRead.get() && size.toLong() > smallMaxBytes) {
                        oversizedAllocations.incrementAndGet()
                    }
                    ByteArray(size)
                },
                syncCacheDirectory = { directory ->
                    if (blockSync.get()) {
                        syncEntered.countDown()
                        releaseSync.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                    }
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                },
            )
        cache.put("k", ByteArray(64) { 1 })
        blockSync.set(true)

        val putThread = Thread { cache.put("k", replacementPayload) }
        putThread.start()
        try {
            assertTrue("put should enter syncCacheDirectory", syncEntered.await(BARRIER_SECONDS, TimeUnit.SECONDS))
            inSmallRead.set(true)
            val smallResult =
                try {
                    cache.getIfSmall("k", maxBytes = smallMaxBytes)
                } finally {
                    inSmallRead.set(false)
                }
            assertNull("getIfSmall must reject oversized replacement during put sync", smallResult)
            assertEquals("getIfSmall must not allocate oversized plaintext", 0, oversizedAllocations.get())
        } finally {
            releaseSync.countDown()
            putThread.join(JOIN_TIMEOUT_MS)
        }

        assertTrue("replacement must remain indexed after getIfSmall miss", cache.contains("k"))
        val published = cache.get("k")
        assertNotNull("replacement must not be poisoned by getIfSmall rejection", published)
        assertTrue("get() must return the full replacement bytes", published!!.contentEquals(replacementPayload))
    }

    /** Pauses a same-key replacement after rename or during sync and asserts both readers reject it. */
    private fun runPausedSameKeyReplacement(
        maxBytes: Long,
        initialPayload: ByteArray,
        replacementPayload: ByteArray,
        pauseAfterRename: Boolean,
    ): DiskByteCache {
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = maxBytes,
                renameFile = { source, target ->
                    val renamed = source.renameTo(target)
                    if (pauseAfterRename && armed.get()) {
                        paused.countDown()
                        release.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                    }
                    renamed
                },
                syncCacheDirectory = { directory ->
                    if (!pauseAfterRename && armed.get()) {
                        paused.countDown()
                        release.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                    }
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                },
            )
        cache.put("k", initialPayload)
        armed.set(true)
        val putThread = Thread { cache.put("k", replacementPayload) }
        putThread.start()
        try {
            assertTrue(
                "same-key replacement should reach pause point",
                paused.await(BARRIER_SECONDS, TimeUnit.SECONDS),
            )
            if (!pauseAfterRename) {
                assertNull("plaintextSizeOf must not report stale metadata during sync", cache.plaintextSizeOf("k"))
            }
            assertNull("get() must not expose unpublished replacement", cache.get("k"))
            assertNull("materialize() must not expose unpublished replacement", cache.materialize("k"))
            assertTrue(
                "rejected materialize() must not leak lease temp files",
                dir.listFiles()?.none { it.name.contains("lease") } ?: true,
            )
        } finally {
            release.countDown()
            putThread.join(JOIN_TIMEOUT_MS)
        }
        return cache
    }

    /** Polls until [thread] reaches [target] state or the barrier deadline expires. */
    private fun awaitThreadState(
        thread: Thread,
        target: Thread.State,
    ): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(BARRIER_SECONDS)
        while (thread.state != target && System.nanoTime() < deadlineNanos) {
            LockSupport.parkNanos(STATE_POLL_NANOS)
        }
        return thread.state == target
    }

    /** Runs `cache.contains(key)` on an independent thread and asserts it finishes without stalling. */
    private fun probeContains(
        cache: DiskByteCache,
        key: String,
    ): Boolean {
        val finished = CountDownLatch(1)
        val result = AtomicReference<Boolean?>()
        val probe =
            Thread {
                try {
                    result.set(cache.contains(key))
                } finally {
                    finished.countDown()
                }
            }
        probe.start()
        val completed = finished.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (completed) probe.join(JOIN_TIMEOUT_MS)
        assertTrue("contains($key) blocked longer than ${PROBE_TIMEOUT_MS}ms", completed)
        return requireNotNull(result.get())
    }

    /** Extracts every `synchronized(this)` block from comment-stripped Kotlin source. */
    private fun synchronizedThisBlocks(source: String): List<String> {
        val codeOnly = stripComments(source)
        return Regex("""\bsynchronized\s*\(\s*this\s*\)""")
            .findAll(codeOnly)
            .map { match ->
                codeOnly.kotlinBlockFrom(
                    openDelimiter = match.range.last + 1,
                    description = "synchronized(this) block at ${match.range.first}",
                    searchForOpening = true,
                )
            }.toList()
    }

    /** Strips Kotlin block and line comments so source gates inspect executable tokens only. */
    private fun stripComments(source: String): String {
        val withoutBlocks = source.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        return withoutBlocks.replace(Regex("""//[^\n]*"""), "")
    }

    /** Directory proxy that pauses `mkdirs()` once armed to test temp-file creation lock hygiene. */
    private class BlockingMkdirsDir(
        delegate: File,
        armed: AtomicBoolean,
        entered: CountDownLatch,
        release: CountDownLatch,
    ) : File(delegate.path) {
        private val target = delegate
        private val isArmed = armed
        private val enteredLatch = entered
        private val releaseLatch = release

        /** Pauses on the latch when armed before delegating directory creation. */
        override fun mkdirs(): Boolean {
            if (isArmed.get()) {
                enteredLatch.countDown()
                try {
                    releaseLatch.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            return target.mkdirs()
        }
    }

    /** Directory proxy that pauses `listFiles()` once armed to test `clear()` sweep lock hygiene. */
    private class BlockingListDir(
        delegate: File,
        armed: AtomicBoolean,
        entered: CountDownLatch,
        release: CountDownLatch,
    ) : File(delegate.path) {
        private val target = delegate
        private val isArmed = armed
        private val enteredLatch = entered
        private val releaseLatch = release

        /** Pauses on the latch when armed before delegating directory listing. */
        override fun listFiles(): Array<File>? {
            if (isArmed.get()) {
                enteredLatch.countDown()
                try {
                    releaseLatch.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            return target.listFiles()
        }
    }

    private companion object {
        const val DISK_BYTE_CACHE_SOURCE = "src/main/java/dev/ipf/whitenoise/android/media/DiskByteCache.kt"
        const val BARRIER_SECONDS = 5L
        const val PROBE_TIMEOUT_MS = 500L
        const val JOIN_TIMEOUT_MS = 5_000L
        const val STATE_POLL_NANOS = 1_000_000L
        val FORBIDDEN_INDEX_MONITOR_TOKENS =
            listOf(
                "syncCacheDirectory",
                "renameFile",
                ".renameTo(",
                ".delete(",
                ".mkdirs(",
                "FileOutputStream(",
                "FileInputStream(",
                ".length(",
                ".listFiles(",
                ".exists(",
                ".isFile",
                "synchronized(publicationLock)",
                "synchronized(hydrationLock)",
            )
    }
}
