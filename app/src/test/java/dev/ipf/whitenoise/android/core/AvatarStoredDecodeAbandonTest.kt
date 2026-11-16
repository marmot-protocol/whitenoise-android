package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Stored avatar decodes nobody waits for any more are skipped, so leaving the chat list starts no workers (#3819). */
@RunWith(RobolectricTestRunner::class)
class AvatarStoredDecodeAbandonTest {
    /** Drops the decoded pixels this test cached. */
    @After
    fun clearCache() {
        AvatarImageLoader.clear()
    }

    /**
     * A stored decode still queued when its only caller leaves, such as a chat-list row the user navigated away
     * from, is skipped instead of starting a worker, and the next request for that avatar reads it normally.
     */
    @Test
    fun abandonedQueuedStoredDecodeIsSkippedAndLoadsLater() =
        runBlocking<Unit> {
            val lifetime = AvatarImageLoader.currentCacheLifetime()
            val release = CompletableDeferred<Unit>()
            val blockersStarted = AtomicInteger()
            val bothBlocking = CompletableDeferred<Unit>()
            val blockers =
                (1..2).map { index ->
                    async {
                        AvatarImageLoader.loadStored("marmot-avatar:owner:blocker-$index@1", lifetime) {
                            if (blockersStarted.incrementAndGet() == 2) bothBlocking.complete(Unit)
                            release.await()
                            Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
                        }
                    }
                }
            val key = "marmot-avatar:owner:abandoned@1"
            val abandonedReads = AtomicInteger()
            try {
                withTimeout(5_000) { bothBlocking.await() }
                val abandoned =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        AvatarImageLoader.loadStored(key, lifetime) {
                            abandonedReads.incrementAndGet()
                            Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
                        }
                    }
                // Joined, so the caller has left and its waiter count is released before the slots free.
                abandoned.cancelAndJoin()
                release.complete(Unit)
                blockers.awaitAll()
                kotlinx.coroutines.delay(SETTLE_MS)
                assertEquals("an abandoned queued decode never reads", 0, abandonedReads.get())
                val laterReads = AtomicInteger()
                assertNotNull(
                    withTimeout(5_000) {
                        AvatarImageLoader.loadStored(key, lifetime) {
                            laterReads.incrementAndGet()
                            Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
                        }
                    },
                )
                assertEquals(1, laterReads.get())
            } finally {
                release.complete(Unit)
            }
        }

    /** A queued stored decode keeps running while any caller still waits, even after another one leaves. */
    @Test
    fun queuedStoredDecodeRunsWhileAnyCallerStillWaits() =
        runBlocking<Unit> {
            val lifetime = AvatarImageLoader.currentCacheLifetime()
            val release = CompletableDeferred<Unit>()
            val blockersStarted = AtomicInteger()
            val bothBlocking = CompletableDeferred<Unit>()
            val blockers =
                (1..2).map { index ->
                    async {
                        AvatarImageLoader.loadStored("marmot-avatar:owner:busy-$index@1", lifetime) {
                            if (blockersStarted.incrementAndGet() == 2) bothBlocking.complete(Unit)
                            release.await()
                            Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
                        }
                    }
                }
            val key = "marmot-avatar:owner:shared@1"
            val reads = AtomicInteger()
            val read: suspend () -> ByteArray? = {
                reads.incrementAndGet()
                Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
            }
            try {
                withTimeout(5_000) { bothBlocking.await() }
                val (leaving, staying) =
                    List(2) {
                        async(start = CoroutineStart.UNDISPATCHED) { AvatarImageLoader.loadStored(key, lifetime, read) }
                    }
                leaving.cancelAndJoin()
                release.complete(Unit)
                assertNotNull(withTimeout(5_000) { staying.await() })
                assertEquals(1, reads.get())
                blockers.awaitAll()
            } finally {
                release.complete(Unit)
            }
        }

    private companion object {
        const val SETTLE_MS = 300L
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
