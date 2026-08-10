package dev.ipf.whitenoise.android.media.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEditorRendererTest {
    @Test
    fun standardAndHighShareRecipeGeometryButUseDifferentOutputPlans() {
        val renderer = renderer()
        val source =
            PhotoEditorSourceInfo(
                encodedSize = EditorPixelSize(4000, 3000),
                orientedSize = EditorPixelSize(4000, 3000),
                exifOrientation = PhotoEditGeometry.EXIF_NORMAL,
                mediaType = "image/jpeg",
                mayHaveAlpha = false,
            )
        val recipe =
            PhotoEditRecipe(
                crop = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f),
                quarterTurnsClockwise = 1,
            )

        val standard = requireNotNull(renderer.outputPlan(source, recipe, MediaQuality.Standard))
        val high = requireNotNull(renderer.outputPlan(source, recipe, MediaQuality.High))
        val sourcePoint = NormalizedPoint(0.25f, 0.3f)
        val standardPoint = standard.geometry.orientedToOutput(sourcePoint)
        val highPoint = high.geometry.orientedToOutput(sourcePoint)

        assertEquals(recipe, standard.geometry.recipe)
        assertEquals(recipe, high.geometry.recipe)
        assertEquals(
            standardPoint.x / standard.geometry.outputSize.width,
            highPoint.x / high.geometry.outputSize.width,
            0.0001f,
        )
        assertEquals(
            standardPoint.y / standard.geometry.outputSize.height,
            highPoint.y / high.geometry.outputSize.height,
            0.0001f,
        )
        assertEquals(2048, maxOf(standard.geometry.outputSize.width, standard.geometry.outputSize.height))
        assertTrue(maxOf(high.geometry.outputSize.width, high.geometry.outputSize.height) > 2048)
        assertTrue(high.effectiveLabel.startsWith("HD"))
    }

    @Test
    fun drawAndEraseAreRenderedWithOneEncodeAndEraserRevealsPhoto() =
        runTest {
            var encodeCount = 0
            val renderer = renderer { encodeCount += 1 }
            val source = solidPng(128, 128, Color.BLUE)
            assertSourcePixel(source, 64, 64) { pixel -> Color.blue(pixel) > Color.red(pixel) }
            val recipe =
                PhotoEditRecipe(
                    strokes =
                        listOf(
                            stroke(
                                id = "draw",
                                mode = PhotoStrokeMode.Draw,
                                color = Color.RED,
                                width = 0.12f,
                                points = listOf(NormalizedPoint(0.05f, 0.5f), NormalizedPoint(0.95f, 0.5f)),
                            ),
                            stroke(
                                id = "erase",
                                mode = PhotoStrokeMode.Erase,
                                color = Color.TRANSPARENT,
                                width = 0.12f,
                                points = listOf(NormalizedPoint(0.42f, 0.5f), NormalizedPoint(0.58f, 0.5f)),
                            ),
                        ),
                )

            val result = renderer.render(source, recipe, MediaQuality.Standard)

            assertTrue("Unexpected render result: $result", result is PhotoEditorRenderResult.Success)
            result as PhotoEditorRenderResult.Success
            assertEquals(1, encodeCount)
            val output = BitmapFactory.decodeByteArray(result.image.bytes, 0, result.image.bytes.size)
            try {
                val redSection = output.getPixel(25, 64)
                val erasedSection = output.getPixel(64, 64)
                assertTrue(
                    "Expected red mark, got #${Integer.toHexString(redSection)}",
                    Color.red(redSection) > Color.blue(redSection),
                )
                assertTrue(
                    "Expected blue photo after erase, got #${Integer.toHexString(erasedSection)}",
                    Color.blue(erasedSection) > Color.red(erasedSection),
                )
            } finally {
                output.recycle()
            }
        }

    @Test
    fun editedOriginalPreservesAlphaAsPngWhileStandardFlattensWhite() =
        runTest {
            val source = transparentPng()
            assertSourcePixel(source, 32, 32) { pixel -> Color.green(pixel) > Color.red(pixel) }
            val renderer = renderer()

            val original = renderer.render(source, PhotoEditRecipe.Original, MediaQuality.Original)
            val standard = renderer.render(source, PhotoEditRecipe.Original, MediaQuality.Standard)

            assertTrue("Unexpected original result: $original", original is PhotoEditorRenderResult.Success)
            assertTrue("Unexpected standard result: $standard", standard is PhotoEditorRenderResult.Success)
            original as PhotoEditorRenderResult.Success
            standard as PhotoEditorRenderResult.Success
            assertEquals("image/png", original.image.mediaType)
            assertEquals("image/jpeg", standard.image.mediaType)
            val originalBitmap = BitmapFactory.decodeByteArray(original.image.bytes, 0, original.image.bytes.size)
            val standardBitmap = BitmapFactory.decodeByteArray(standard.image.bytes, 0, standard.image.bytes.size)
            try {
                assertEquals(0, Color.alpha(originalBitmap.getPixel(0, 0)))
                val flattened = standardBitmap.getPixel(0, 0)
                assertEquals(255, Color.alpha(flattened))
                assertTrue(
                    "Expected white flatten, got #${Integer.toHexString(flattened)}",
                    Color.red(flattened) > 245 && Color.green(flattened) > 245 && Color.blue(flattened) > 245,
                )
            } finally {
                originalBitmap.recycle()
                standardBitmap.recycle()
            }
        }

    @Test
    fun finalizerEnforcesEncodedCapAndStillAttemptsOnlyOneEncode() {
        var encodeCount = 0
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)

        val finalized =
            MediaPipeline.finalizeRenderedImage(
                rendered = bitmap,
                plan =
                    MediaPipeline.OutputPlan(
                        maxEdgePx = 64,
                        maxPixels = 4096,
                        format = MediaPipeline.RenderedImageFormat.Png,
                        jpegQuality = 100,
                        maxEncodedBytes = 8,
                    ),
                onEncode = { encodeCount += 1 },
            )

        assertNull(finalized)
        assertEquals(1, encodeCount)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun malformedAndAnimatedSourcesFailBeforeEncode() =
        runTest {
            var encodeCount = 0
            val renderer = renderer { encodeCount += 1 }

            val malformed = renderer.render(byteArrayOf(1, 2, 3), PhotoEditRecipe.Original, MediaQuality.Standard)
            val gif =
                renderer.render(
                    "GIF89a".encodeToByteArray(),
                    PhotoEditRecipe.Original,
                    MediaQuality.Standard,
                )
            val apng =
                renderer.render(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) +
                        pngChunk("acTL", ByteArray(8)),
                    PhotoEditRecipe.Original,
                    MediaQuality.Standard,
                )

            assertEquals(
                PhotoEditorRenderResult.InvalidSource(PhotoEditorSourceFailure.Unsupported),
                malformed,
            )
            assertEquals(
                PhotoEditorRenderResult.InvalidSource(PhotoEditorSourceFailure.Animated),
                gif,
            )
            assertEquals(
                PhotoEditorRenderResult.InvalidSource(PhotoEditorSourceFailure.Animated),
                apng,
            )
            assertEquals(0, encodeCount)
        }

    private fun pngChunk(
        type: String,
        data: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(data.size).array())
            output.write(type.toByteArray(Charsets.US_ASCII))
            output.write(data)
            output.write(ByteArray(Int.SIZE_BYTES))
            output.toByteArray()
        }

    private fun renderer(onEncode: () -> Unit = {}) =
        PhotoEditorRenderer(
            renderDispatcher = UnconfinedTestDispatcher(),
            memoryBudgetBytes = { 256L * 1024L * 1024L },
            onEncode = onEncode,
        )

    private fun stroke(
        id: String,
        mode: PhotoStrokeMode,
        color: Int,
        width: Float,
        points: List<NormalizedPoint>,
    ) = PhotoEditStroke(
        id = id,
        mode = mode,
        widthFraction = width,
        colorArgb = color,
        points = points,
    )

    private fun solidPng(
        width: Int,
        height: Int,
        color: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return encodePng(bitmap)
    }

    private fun transparentPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        for (y in 16 until 48) {
            for (x in 16 until 48) bitmap.setPixel(x, y, Color.GREEN)
        }
        return encodePng(bitmap)
    }

    private fun encodePng(bitmap: Bitmap): ByteArray =
        try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }

    private fun assertSourcePixel(
        bytes: ByteArray,
        x: Int,
        y: Int,
        predicate: (Int) -> Boolean,
    ) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            val pixel = bitmap.getPixel(x, y)
            assertTrue("Unexpected fixture pixel #${Integer.toHexString(pixel)}", predicate(pixel))
        } finally {
            bitmap.recycle()
        }
    }
}
