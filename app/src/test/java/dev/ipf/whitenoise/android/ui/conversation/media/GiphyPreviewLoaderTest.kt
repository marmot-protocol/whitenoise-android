package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.whitenoise.android.core.RemoteGiphyMedia
import dev.ipf.whitenoise.android.media.sandbox.AnimationSessionControl
import dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GiphyPreviewLoaderTest {
    /** Scrolling back to a result reuses bytes, while closing the picker retires its private cache. */
    @Test
    fun revisitedTilesReuseBytesWithinThePicker() =
        runTest {
            var requests = 0
            val bytes = byteArrayOf(1, 2, 3)
            val request = RemoteGiphyMedia.ImageRequest("https://media.giphy.com/media/cat/giphy.gif", "image/gif")
            val fetch: suspend (RemoteGiphyMedia.ImageRequest) -> ByteArray? = {
                requests++
                bytes
            }
            val loader = GiphyPreviewLoader(fetch)
            assertSame(bytes, loader.encodedBytes(request))
            assertSame(bytes, loader.encodedBytes(request))
            assertEquals(1, requests)
            assertSame(bytes, GiphyPreviewLoader(fetch).encodedBytes(request))
            assertEquals(2, requests)
        }

    /** Retaining preview responses cannot grow past the existing byte-bounded cache budget. */
    @Test
    fun previewByteCacheEvictsOlderResponses() =
        runTest {
            var requests = 0
            val loader =
                GiphyPreviewLoader {
                    requests++
                    ByteArray(GIPHY_MAX_BODY_BYTES)
                }
            val media = (0..4).map { RemoteGiphyMedia.ImageRequest("https://media.giphy.com/media/cat$it/giphy.gif", "image/gif") }
            media.forEach { assertEquals(GIPHY_MAX_BODY_BYTES, loader.encodedBytes(it)?.size) }
            assertEquals(5, requests)
            loader.encodedBytes(media.last())
            assertEquals(5, requests)
            loader.encodedBytes(media.first())
            assertEquals(6, requests)
        }

    /** A worker stream with only a first frame must never enable the send action. */
    @Test
    fun refusesAStalledAnimation() =
        runTest {
            val drawable = fixture()
            try {
                assertNull(captureGiphyPreview(drawable))
                assertNull(drawable.callback)
            } finally {
                drawable.close()
            }
        }

    /** Captured pixels survive worker retirement and are released with the tile. */
    @Test
    fun loopOwnsItsPixelsAfterWorkerCloses() =
        runTest {
            val drawable = fixture(secondColor = Color.BLUE)
            val preview =
                try {
                    requireNotNull(captureGiphyPreview(drawable))
                } finally {
                    drawable.close()
                }
            val bitmaps = preview.frames.map { it.image.asAndroidBitmap() }
            try {
                assertEquals(listOf(Color.RED, Color.BLUE), bitmaps.map { it.getPixel(0, 0) })
            } finally {
                preview.release()
            }
            assertTrue(bitmaps.all { it.isRecycled })
        }

    /** Supplies the real drawable with deterministic, worker-shaped chunks of decoded pixels. */
    private fun fixture(secondColor: Int? = null): IsolatedAnimationDrawable {
        lateinit var drawable: IsolatedAnimationDrawable
        val session =
            object : AnimationSessionControl {
                override fun play() {
                    secondColor?.let { drawable.accept(pixels(it)) }
                }

                override fun pause() = Unit

                override fun isOpen() = true

                override fun close() = Unit
            }
        drawable = IsolatedAnimationDrawable(pixels(Color.RED), session)
        return drawable
    }

    private fun pixels(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }
}
