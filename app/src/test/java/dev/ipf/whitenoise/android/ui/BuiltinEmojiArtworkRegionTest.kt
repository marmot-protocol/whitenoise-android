package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.imageio.ImageIO

/**
 * Built-ins render at the same visual size as Unicode and uploaded emoji only when every crop is the
 * artwork's tight square. Reads the shipped PNGs, so a redrawn pose cannot silently shrink again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BuiltinEmojiArtworkRegionTest {
    @Test
    fun everyBuiltinCropIsTheTightSquareAroundItsArtwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (shortcode in EmojiShortcodes.builtins) {
            val drawable = checkNotNull(EmojiShortcodes.builtinDrawable(shortcode))
            val name = context.resources.getResourceEntryName(drawable)
            val image = ImageIO.read(File("src/main/res/drawable-nodpi/$name.png"))
            var left = image.width
            var top = image.height
            var right = -1
            var bottom = -1
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    if (image.getRGB(x, y) ushr ALPHA_SHIFT != 0) {
                        left = minOf(left, x)
                        top = minOf(top, y)
                        right = maxOf(right, x)
                        bottom = maxOf(bottom, y)
                    }
                }
            }
            val width = right - left + 1
            val height = bottom - top + 1
            val region = EmojiShortcodes.builtinArtworkRegion(drawable)
            if (width == image.width && height == image.height) {
                assertNull("$shortcode fills its canvas", region)
                continue
            }
            checkNotNull(region) { "$shortcode needs a crop around its ${width}x$height artwork" }
            val side = maxOf(width, height)
            assertEquals("$shortcode crop width", side, region.width)
            assertEquals("$shortcode crop height", side, region.height)
            assertTrue("$shortcode crop stays on the canvas", region.left >= 0 && region.top >= 0)
            assertTrue(
                "$shortcode crop stays on the canvas",
                region.right <= image.width && region.bottom <= image.height,
            )
            assertTrue(
                "$shortcode crop contains its artwork",
                region.left <= left && region.top <= top && region.right > right && region.bottom > bottom,
            )
            assertTrue(
                "$shortcode artwork is centred in its crop",
                kotlin.math.abs((left - region.left) - (region.right - 1 - right)) <= 1 &&
                    kotlin.math.abs((top - region.top) - (region.bottom - 1 - bottom)) <= 1,
            )
        }
    }

    private companion object {
        const val ALPHA_SHIFT = 24
    }
}
