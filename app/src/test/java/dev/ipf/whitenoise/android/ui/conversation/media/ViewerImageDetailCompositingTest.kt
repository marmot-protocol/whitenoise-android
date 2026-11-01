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
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
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

    @Test
    fun detailReplacesSampledPixelsOverBlack() = checkReplacement(Color.Black)

    @Test
    fun detailReplacesSampledPixelsOverBlue() = checkReplacement(Color.Blue)

    private fun checkReplacement(background: Color) {
        val sampled = sampledPreview()
        val detail = detailPixels()
        val source = ViewerOriginalImage(byteArrayOf(1), ImageInspection(100, 100, "image/png", 1))
        var scale by mutableStateOf(2f)
        var active by mutableStateOf(true)
        var decoded = false
        rule.setContent {
            Box(Modifier.size(100.dp).background(background).testTag("composited")) {
                ImageViewerViewport(
                    sourceKey = source,
                    imageWidth = 100,
                    imageHeight = 100,
                    scale = scale,
                    offset = Offset.Zero,
                    onScaleChange = {},
                    onOffsetChange = {},
                    onChromeToggle = {},
                    imageLayer = { geometry, preview ->
                        ViewerImageDetailLayer(
                            source,
                            geometry,
                            active,
                            preview = preview,
                            decode = { _, _ ->
                                decoded = true
                                ViewerImageDetail(detail, MediaImageRegion(40, 40, 60, 60))
                            },
                        )
                    },
                ) { modifier ->
                    Image(sampled.asImageBitmap(), null, modifier, contentScale = ContentScale.FillBounds)
                }
            }
        }
        rule.waitUntil(10_000) { decoded }
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

    private fun sampledPreview(): Bitmap =
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
            for (y in 40 until 60) {
                for (x in 40 until 47) setPixel(x, y, 0x80ff0000.toInt())
            }
        }

    private fun detailPixels(): Bitmap =
        Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.TRANSPARENT)
            for (y in 0 until 20) {
                for (x in 0 until 7) setPixel(x, y, 0x80ff0000.toInt())
                for (x in 14 until 20) setPixel(x, y, android.graphics.Color.YELLOW)
            }
        }
}
