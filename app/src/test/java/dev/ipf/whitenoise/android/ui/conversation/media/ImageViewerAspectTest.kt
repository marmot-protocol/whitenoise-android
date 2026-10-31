package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real pixels detect preview/source aspect disagreement that a geometry-only test cannot expose. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "mdpi")
class ImageViewerAspectTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aOnePixelSourceKeepsItsFractionalFitBeforeDeepZoom() = renderAndCheck(sourceHeight = 1, scale = 1024f)

    @Test
    fun aNonIntegralSampledAspectStillUsesOriginalSourceGeometry() = renderAndCheck(sourceHeight = 7, scale = 128f)

    private fun renderAndCheck(
        sourceHeight: Int,
        scale: Float,
    ) {
        val sampled =
            Bitmap.createBitmap(2560, 1, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED)
            }
        rule.setContent {
            Box(Modifier.size(400.dp).background(Color.Black).testTag("aspect-root")) {
                ImageViewerViewport(
                    sourceKey = "narrow",
                    imageWidth = 32768,
                    imageHeight = sourceHeight,
                    scale = scale,
                    offset = Offset.Zero,
                    onScaleChange = {},
                    onOffsetChange = {},
                    onChromeToggle = {},
                ) { imageModifier ->
                    Image(
                        sampled.asImageBitmap(),
                        "sampled source",
                        modifier = imageModifier,
                        contentScale = ContentScale.FillBounds,
                    )
                }
            }
        }
        val pixels = rule.onNodeWithTag("aspect-root").captureToImage().toPixelMap()
        val coloredRows =
            (0 until pixels.height).count { y ->
                val pixel = pixels[pixels.width / 2, y]
                pixel.red > 0.5f && pixel.green < 0.2f && pixel.blue < 0.2f
            }
        val expected = pixels.width * sourceHeight.toFloat() / 32768 * scale
        assertTrue(
            "original source band expected $expected px, got $coloredRows",
            coloredRows in (expected - 2).toInt()..(expected + 2).toInt(),
        )
    }
}
