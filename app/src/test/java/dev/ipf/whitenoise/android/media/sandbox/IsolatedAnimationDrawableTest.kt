package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IsolatedAnimationDrawableTest {
    @Test
    fun lateWorkerFramesCannotChangePausedPixelsAndPlaybackCanResume() {
        val session =
            object : AnimationSessionControl {
                override fun play() = Unit

                override fun pause() = Unit

                override fun isOpen() = true

                override fun close() = Unit
            }
        val drawable = IsolatedAnimationDrawable(pixels(Color.RED), session)
        val displayed = pixels(Color.TRANSPARENT)
        try {
            drawable.setBounds(0, 0, 2, 2)
            drawable.start()
            drawable.accept(pixels(Color.BLUE))
            assertEquals(2L, drawable.presentedFrame)
            drawable.stop()
            val late = pixels(Color.RED)
            drawable.accept(late)
            assertTrue(late.isRecycled)
            assertEquals(2L, drawable.presentedFrame)
            drawable.draw(Canvas(displayed))
            assertEquals(Color.BLUE, displayed.getPixel(0, 0))
            drawable.start()
            drawable.accept(pixels(Color.GREEN))
            assertEquals(3L, drawable.presentedFrame)
            drawable.draw(Canvas(displayed))
            assertEquals(Color.GREEN, displayed.getPixel(0, 0))
        } finally {
            drawable.close()
            displayed.recycle()
        }
    }

    private fun pixels(color: Int): Bitmap =
        Bitmap
            .createBitmap(
                2,
                2,
                Bitmap.Config.ARGB_8888,
            ).apply { eraseColor(color) }
}
