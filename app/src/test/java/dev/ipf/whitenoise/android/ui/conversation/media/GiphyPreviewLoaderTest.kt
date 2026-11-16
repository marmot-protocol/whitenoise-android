package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ipf.whitenoise.android.media.sandbox.AnimationSessionControl
import dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun pixels(color: Int): Bitmap =
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}
