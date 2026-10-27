package dev.ipf.whitenoise.android.state

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaMemoryCacheEvictionTest {
    /** L1 trim releases counted bytes without recycling drawn pixels or erasing retained disk media. */
    @Test
    fun backgroundTrimPreservesDisplayedPixelsAndDurableMedia() =
        runBlocking {
            MediaDownloadIntegrationFixture().use { fixture ->
                val state = fixture.state
                val bytes = byteArrayOf(1, 2, 3)
                val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
                state.cacheMediaPlaintext("trim-media", bytes)
                state.cacheMediaThumbnail("trim-media", bitmap)
                fixture.disk.put("trim-media", bytes)
                val revision = state.mediaCacheRevision.value
                val thumbnailRevision = state.mediaThumbnailRevision.value
                state.trimPresentationMemory(dev.ipf.whitenoise.android.media.PresentationMemoryTrim.EMPTY)
                org.junit.Assert.assertNull(state.cachedMediaPlaintext("trim-media"))
                org.junit.Assert.assertNull(state.cachedMediaThumbnail("trim-media"))
                org.junit.Assert.assertFalse(bitmap.isRecycled)
                org.junit.Assert.assertArrayEquals(bytes, fixture.disk.get("trim-media"))
                assertEquals(revision + 1, state.mediaCacheRevision.value)
                assertEquals(thumbnailRevision + 1, state.mediaThumbnailRevision.value)
                state.trimPresentationMemory(dev.ipf.whitenoise.android.media.PresentationMemoryTrim.EMPTY)
                assertEquals(revision + 1, state.mediaCacheRevision.value)
                assertEquals(thumbnailRevision + 1, state.mediaThumbnailRevision.value)
                assertTrue("trim must not start native acquisition", fixture.calls.isEmpty())
            }
        }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun removeMediaMemoryCacheKeys_usesSuppliedDispatcherForBothLrus() {
        val removals = mutableListOf<String>()
        val threads = mutableListOf<String>()

        newSingleThreadContext("media-memory-evict-main").use { dispatcher ->
            runBlocking {
                removeMediaMemoryCacheKeys(
                    cacheKeys = listOf("one", "two"),
                    dispatcher = dispatcher,
                    removeEntry = { key ->
                        removals += key
                        threads += Thread.currentThread().name
                    },
                )
            }
        }

        assertEquals(
            listOf("one", "two"),
            removals,
        )
        assertTrue(
            "all removals should run on the supplied dispatcher thread, got $threads",
            threads.all { it.startsWith("media-memory-evict-main") },
        )
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun removeMediaMemoryCacheKeys_movesBackgroundCallerToAndroidMainBeforeGuardedRemoval() {
        val removals = mutableListOf<String>()
        val failure = AtomicReference<Throwable>()
        val completed = AtomicBoolean(false)

        newSingleThreadContext("media-memory-evict-background").use { background ->
            val job =
                CoroutineScope(background).launch {
                    runCatching {
                        removeMediaMemoryCacheKeys(
                            cacheKeys = listOf("one", "two"),
                            dispatcher = Dispatchers.Main.immediate,
                            removeEntry = { key ->
                                assertMainThread(checkingEnabled = true) { "media L1 removal" }
                                removals += key
                            },
                        )
                    }.onFailure(failure::set)
                    completed.set(true)
                }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!completed.get() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("background eviction did not complete", completed.get())
            runBlocking { job.join() }
        }

        failure.get()?.let { throw it }
        assertEquals(listOf("one", "two"), removals)
    }
}
