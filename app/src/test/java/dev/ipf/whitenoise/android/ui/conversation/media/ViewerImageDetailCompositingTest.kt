package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Capture the composed viewport: inspecting the detail bitmap alone misses alpha accumulation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "mdpi")
class ViewerImageDetailCompositingTest {
    @get:Rule val rule = createComposeRule()

    /** Transparent detail replaces the sampled pixels over the default black surface. */
    @Test
    fun detailReplacesSampledPixelsOverBlack() = checkReplacement(Color.Black)

    /** The replacement keeps the chosen surface visible through transparent source pixels. */
    @Test
    fun detailReplacesSampledPixelsOverBlue() = checkReplacement(Color.Blue)

    /** Exercises the presentation seam independently of whether a codec crop would improve this tiny source. */
    private fun checkReplacement(background: Color) {
        val sampled = sampledPreview()
        val detail = detailPixels()
        var scale by mutableStateOf(2f)
        var active by mutableStateOf(true)
        rule.setContent {
            Box(Modifier.size(100.dp).background(background).testTag("composited")) {
                ImageViewerViewport(
                    sourceKey = sampled,
                    imageWidth = 100,
                    imageHeight = 100,
                    scale = scale,
                    offset = Offset.Zero,
                    onScaleChange = {},
                    onOffsetChange = {},
                    onChromeToggle = {},
                    imageLayer = { geometry, preview ->
                        viewerImageDetailPresentation(
                            current =
                                if (active && scale > 1f) {
                                    ViewerImageDetail(detail, MediaImageRegion(40, 40, 60, 60))
                                } else {
                                    null
                                },
                            geometry = geometry,
                            preview = preview,
                        )
                    },
                ) { modifier ->
                    Image(sampled.asImageBitmap(), null, modifier, contentScale = ContentScale.FillBounds)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("conversation.media.viewer.detail").assertExists()
        val pixels = rule.onNodeWithTag("composited").captureToImage().toPixelMap()
        // Source x=43 is half red; x=50 is a transparent hole over the green sampled blur.
        val red = pixels[36, 50]
        assertEquals(128f / 255, red.red, 0.02f)
        assertEquals(0f, red.green, 0.02f)
        assertEquals(background.blue * (127f / 255), red.blue, 0.02f)
        assertEquals(background, pixels[50, 50])
        assertEquals(Color.Yellow, pixels[64, 50])
        assertEquals(Color.Green, pixels[15, 50]) // Outside the detail rectangle retains the preview.
        rule.runOnIdle { scale = 1f }
        rule.onNodeWithTag("conversation.media.viewer.detail").assertDoesNotExist()
        assertEquals(Color.Green, rule.onNodeWithTag("composited").captureToImage().toPixelMap()[50, 50])
        rule.runOnIdle {
            scale = 2f
            active = false
        }
        rule.onNodeWithTag("conversation.media.viewer.detail").assertDoesNotExist()
        assertEquals(Color.Green, rule.onNodeWithTag("composited").captureToImage().toPixelMap()[50, 50])
    }

    /** Seeds an intentionally blurred base so alpha accumulation remains observable in the capture. */
    private fun sampledPreview(): Bitmap =
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            for (y in 40 until 60) {
                for (x in 40 until 47) setPixel(x, y, 0x80ff0000.toInt())
            }
        }

    /** Provides opaque, partial-alpha and transparent pixels at known crop positions. */
    private fun detailPixels(): Bitmap =
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.TRANSPARENT)
            for (y in 0 until 20) {
                for (x in 0 until 7) setPixel(x, y, 0x80ff0000.toInt())
                for (x in 14 until 20) setPixel(x, y, android.graphics.Color.YELLOW)
            }
        }
}
