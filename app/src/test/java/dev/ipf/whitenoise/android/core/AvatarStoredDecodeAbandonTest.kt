package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.media.MediaTrustDomain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

/**
 * A stored avatar decode that only visible rows asked for is skipped once they all leave, so leaving the chat list
 * starts no workers (#3819). A caller that stops waiting on purpose, such as a notification warm-up, keeps it.
 */
@RunWith(RobolectricTestRunner::class)
class AvatarStoredDecodeAbandonTest {
    private val lifetime = AvatarImageLoader.currentCacheLifetime()
    private val release = CompletableDeferred<Unit>()

    /** Frees the stored lane and drops the decoded pixels this test cached. */
    @After
    fun clearCache() {
        release.complete(Unit)
        AvatarImageLoader.clear()
    }

    /** A queued decode whose only caller was a row that left is skipped, and the next request reads it normally. */
    @Test
    fun abandonedRowDecodeIsSkippedAndLoadsLater() =
        runBlocking<Unit> {
            val blockers = fillStoredLane()
            val key = "marmot-avatar:owner:abandoned@1"
            val abandonedReads = AtomicInteger()
            val row = async(start = CoroutineStart.UNDISPATCHED) { load(key, abandonable = true) { abandonedReads } }
            row.cancelAndJoin()
            release.complete(Unit)
            blockers.awaitAll()
            awaitSettled(key)
            assertEquals("an abandoned queued decode never reads", 0, abandonedReads.get())
            val laterReads = AtomicInteger()
            assertNotNull(withTimeout(WAIT_MS) { load(key, abandonable = true) { laterReads } })
            assertEquals(1, laterReads.get())
        }

    /** A warm-up that stops waiting on purpose still has its queued decode run and cached for the next frame. */
    @Test
    fun cancelledWarmUpStillDecodesAndCaches() =
        runBlocking<Unit> {
            val blockers = fillStoredLane()
            val key = "marmot-avatar:owner:warmed@1"
            val reads = AtomicInteger()
            val warm = async(start = CoroutineStart.UNDISPATCHED) { load(key, abandonable = false) { reads } }
            warm.cancelAndJoin()
            release.complete(Unit)
            blockers.awaitAll()
            awaitSettled(key)
            assertEquals(1, reads.get())
            assertNotNull("the warm-up's pixels are cached", AvatarImageLoader.cachedImage(key))
        }

    /** A row's queued decode that a warm-up also joined keeps running after both leave. */
    @Test
    fun aWarmUpJoiningARowDecodePinsIt() =
        runBlocking<Unit> {
            val blockers = fillStoredLane()
            val key = "marmot-avatar:owner:pinned@1"
            val reads = AtomicInteger()
            val row = async(start = CoroutineStart.UNDISPATCHED) { load(key, abandonable = true) { reads } }
            val warm = async(start = CoroutineStart.UNDISPATCHED) { load(key, abandonable = false) { reads } }
            row.cancelAndJoin()
            warm.cancelAndJoin()
            release.complete(Unit)
            blockers.awaitAll()
            awaitSettled(key)
            assertEquals(1, reads.get())
            assertNotNull(AvatarImageLoader.cachedImage(key))
        }

    /** A queued row decode keeps running while any row still waits, even after another one leaves. */
    @Test
    fun queuedRowDecodeRunsWhileAnyRowStillWaits() =
        runBlocking<Unit> {
            val blockers = fillStoredLane()
            val key = "marmot-avatar:owner:shared@1"
            val reads = AtomicInteger()
            val (leaving, staying) =
                List(2) { async(start = CoroutineStart.UNDISPATCHED) { load(key, abandonable = true) { reads } } }
            leaving.cancelAndJoin()
            release.complete(Unit)
            assertNotNull(withTimeout(WAIT_MS) { staying.await() })
            assertEquals(1, reads.get())
            blockers.awaitAll()
        }

    /** Holds both stored-lane slots until [release], so later stored decodes queue behind them. */
    private suspend fun CoroutineScope.fillStoredLane(): List<Deferred<Any?>> {
        val started = AtomicInteger()
        val bothBlocking = CompletableDeferred<Unit>()
        val blockers =
            (1..2).map { index ->
                async {
                    AvatarImageLoader.loadStored("marmot-avatar:owner:blocker-$index@1", lifetime) {
                        if (started.incrementAndGet() == 2) bothBlocking.complete(Unit)
                        release.await()
                        onePixelPng()
                    }
                }
            }
        withTimeout(WAIT_MS) { bothBlocking.await() }
        return blockers
    }

    /** Loads [key] as a row ([abandonable]) or a warm-up, counting each actual read in [reads]. */
    private suspend fun load(
        key: String,
        abandonable: Boolean,
        reads: () -> AtomicInteger,
    ) = AvatarImageLoader.loadStored(key, lifetime, MediaTrustDomain.Unique, abandonable) {
        reads().incrementAndGet()
        onePixelPng()
    }

    /** Waits until [key]'s detached work has either been skipped or published, with no fixed sleep. */
    private suspend fun awaitSettled(key: String) =
        withTimeout(WAIT_MS) {
            while (AvatarImageLoader.isInFlightForTest(key)) delay(POLL_MS)
        }

    /** A valid one-pixel PNG, small enough to decode instantly. */
    private fun onePixelPng(): ByteArray = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 5L
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
