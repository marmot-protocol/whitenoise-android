package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual pending-video decode handoff, including a decoder ignoring interruption. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PendingVideoDecodeOwnershipTest {
    @Test
    fun cancellationRecyclesTheLatePosterBeforeTilePublication() =
        runBlocking {
            val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var published: Bitmap? = null
            val pending =
                launch(Dispatchers.Default) {
                    published =
                        decodePendingVideoPosterBitmap(byteArrayOf(1)) {
                            entered.countDown()
                            while (release.count > 0) {
                                try {
                                    release.await(20, TimeUnit.MILLISECONDS)
                                } catch (_: InterruptedException) {
                                    // Model a native poster decode that still has to return its allocated bitmap.
                                }
                            }
                            bitmap
                        }
                }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                pending.cancel()
            } finally {
                release.countDown()
            }
            pending.cancelAndJoin()
            assertTrue(bitmap.isRecycled)
            assertNull(published)
        }

    @Test
    fun successfulDecodeTransfersTheLivePosterToItsCaller() =
        runBlocking {
            val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
            try {
                val decoded = decodePendingVideoPosterBitmap(byteArrayOf(1)) { bitmap }
                assertSame(bitmap, decoded)
                assertFalse(bitmap.isRecycled)
            } finally {
                bitmap.recycle()
            }
        }
}
