package dev.ipf.whitenoise.android.media

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import javax.crypto.spec.SecretKeySpec

/**
 * Guards [DiskByteCache.containsAfterHydration] as the definitive off-main probe: a same-key
 * replacement detached during its directory sync must never read as absent there, because
 * thumbnail reconciliation treats that answer as proof the source is gone.
 */
class DiskByteCacheDefinitiveProbeTest {
    private lateinit var dir: File
    private val keyProvider =
        DiskByteCacheKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }

    /** Creates an isolated temporary cache directory for each test. */
    @Before
    fun setUp() {
        dir = Files.createTempDirectory("disk-cache-definitive-probe-test").toFile()
    }

    /** Deletes the temporary cache directory after each test. */
    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Verifies the definitive probe waits out a stalled same-key replacement and then reports it present. */
    @Test
    fun definitiveProbeWaitsForSameKeyReplacementInsteadOfReportingAbsence() {
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val cache =
            DiskByteCache(
                cacheDir = dir,
                keyProvider = keyProvider,
                maxBytes = 1024L,
                syncCacheDirectory = { directory ->
                    if (armed.get()) {
                        paused.countDown()
                        release.await(BARRIER_SECONDS, TimeUnit.SECONDS)
                    }
                    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
                },
            )
        cache.put("k", ByteArray(32) { 1 })
        armed.set(true)
        val putThread = Thread { cache.put("k", ByteArray(128) { 2 }) }
        val probeResult = AtomicReference<Boolean?>(null)
        val probeThread = Thread { probeResult.set(cache.containsAfterHydration("k")) }
        putThread.start()
        try {
            assertTrue("replacement should reach directory sync", paused.await(BARRIER_SECONDS, TimeUnit.SECONDS))
            assertFalse("main-thread contains() stays non-blocking and misses until the swap", cache.contains("k"))
            probeThread.start()
            assertTrue("definitive probe must wait for the in-flight publication", awaitBlocked(probeThread))
        } finally {
            release.countDown()
            putThread.join(JOIN_TIMEOUT_MS)
            probeThread.join(JOIN_TIMEOUT_MS)
        }
        assertTrue("definitive probe must report the published replacement", probeResult.get() == true)
    }

    /** Polls until [thread] is blocked on a monitor or the barrier deadline expires. */
    private fun awaitBlocked(thread: Thread): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(BARRIER_SECONDS)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadlineNanos) {
            LockSupport.parkNanos(STATE_POLL_NANOS)
        }
        return thread.state == Thread.State.BLOCKED
    }

    private companion object {
        const val BARRIER_SECONDS = 5L
        const val JOIN_TIMEOUT_MS = 5_000L
        const val STATE_POLL_NANOS = 1_000_000L
    }
}
