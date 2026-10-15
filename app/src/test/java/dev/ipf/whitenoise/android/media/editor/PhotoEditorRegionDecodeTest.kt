package dev.ipf.whitenoise.android.media.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEditorRegionDecodeTest {
    @Test
    fun oversizedNativeIntermediateReportsMemoryLimitBeforeDecode() =
        runTest {
            val source = png(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
            var regionCalls = 0
            var encodeCalls = 0
            IsolatedMediaDecoder.installTestTransport(
                object : MediaDecodeTransport by TestMediaDecodeTransport {
                    override fun inspect(bytes: ByteArray) = ImageInspection(12000, 9000, "image/png", 1)

                    override fun region(
                        bytes: ByteArray,
                        region: MediaImageRegion,
                        edge: Int,
                    ): Bitmap? {
                        regionCalls += 1
                        return null
                    }
                },
            )
            try {
                val renderer =
                    PhotoEditorRenderer(
                        renderDispatcher = UnconfinedTestDispatcher(),
                        memoryBudgetBytes = { Long.MAX_VALUE },
                        onEncode = { encodeCalls += 1 },
                    )
                val result =
                    renderer.render(
                        source,
                        PhotoEditRecipe(crop = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)),
                        MediaQuality.High,
                    )
                assertEquals(PhotoEditorRenderResult.MemoryLimit, result)
                assertEquals(0, regionCalls)
                assertEquals(0, encodeCalls)
            } finally {
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            }
        }

    @Test
    fun regionPixelsMatchIndependentExifCornerExpectations() =
        runTest {
            val source = quadrantPng()
            val expected =
                listOf(
                    listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
                    listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
                    listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                    listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
                    listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
                    listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                    listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
                    listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
                )
            try {
                for (orientation in 1..8) {
                    val inspection = ImageInspection(5000, 200, "image/png", orientation)
                    IsolatedMediaDecoder.installTestTransport(
                        object : MediaDecodeTransport by TestMediaDecodeTransport {
                            override fun inspect(bytes: ByteArray) = inspection
                        },
                    )
                    val result =
                        PhotoEditorRenderer(
                            renderDispatcher = UnconfinedTestDispatcher(),
                            memoryBudgetBytes = { 256L * 1024 * 1024 },
                        ).render(
                            source,
                            PhotoEditRecipe(crop = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)),
                            MediaQuality.Original,
                        )
                    assertTrue("EXIF $orientation: $result", result is PhotoEditorRenderResult.Success)
                    val bytes = (result as PhotoEditorRenderResult.Success).image.bytes
                    val output = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    try {
                        val corners =
                            listOf(
                                output.getPixel(output.width / 4, output.height / 4),
                                output.getPixel(output.width * 3 / 4, output.height / 4),
                                output.getPixel(output.width / 4, output.height * 3 / 4),
                                output.getPixel(output.width * 3 / 4, output.height * 3 / 4),
                            )
                        assertEquals("EXIF $orientation", expected[orientation - 1], corners)
                    } finally {
                        output.recycle()
                    }
                }
            } finally {
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            }
        }

    private fun quadrantPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(5000, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)
        canvas.drawRect(2500f, 0f, 5000f, 100f, Paint().apply { color = Color.GREEN })
        canvas.drawRect(0f, 100f, 2500f, 200f, Paint().apply { color = Color.BLUE })
        canvas.drawRect(2500f, 100f, 5000f, 200f, Paint().apply { color = Color.YELLOW })
        return png(bitmap)
    }

    private fun png(bitmap: Bitmap): ByteArray =
        try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
}
