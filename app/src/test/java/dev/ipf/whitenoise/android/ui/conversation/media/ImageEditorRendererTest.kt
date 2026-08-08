package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ImageEditorRendererTest {
    private val bitmaps = mutableListOf<Bitmap>()

    @After
    fun recycleBitmaps() {
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test
    fun cropAndRotationProduceExpectedOutputDimensions() {
        val source = bitmap(400, 200, Color.BLUE)
        val state =
            ImageEditState(
                crop = NormalizedRect(0f, 0f, 0.5f, 1f),
                quarterTurns = 1,
            )

        val rendered = ImageEditorRenderer.render(source, state)
        assertNotNull(rendered)
        val output = track(requireNotNull(rendered))

        assertEquals(100, output.width)
        assertEquals(400, output.height)
    }

    @Test
    fun drawingMapsNormalizedCoordinatesOntoTheCroppedOutput() {
        val source = bitmap(100, 100, Color.BLUE)
        val state =
            ImageEditState(
                strokes =
                    listOf(
                        EditorStroke.bounded(
                            points = listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f)),
                            colorArgb = Color.RED,
                            widthFraction = 0.1f,
                            eraser = false,
                        ),
                    ),
            )

        val rendered = ImageEditorRenderer.render(source, state)
        assertNotNull(rendered)
        val output = track(requireNotNull(rendered))

        assertEquals(Color.RED, output.getPixel(50, 50))
    }

    @Test
    fun eraserRemovesOnlyMarkupAndRevealsTheSource() {
        val source = bitmap(100, 100, Color.BLUE)
        val diagonal = listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f))
        val state =
            ImageEditState(
                strokes =
                    listOf(
                        EditorStroke.bounded(diagonal, Color.RED, 0.1f, eraser = false),
                        EditorStroke.bounded(diagonal, Color.TRANSPARENT, 0.12f, eraser = true),
                    ),
            )

        val rendered = ImageEditorRenderer.render(source, state)
        assertNotNull(rendered)
        val output = track(requireNotNull(rendered))

        assertEquals(Color.BLUE, output.getPixel(50, 50))
    }

    @Test
    fun transparentSourcePixelsRemainTransparentUntilTheExistingSendPipelineFlattensThem() {
        val source = track(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        source.eraseColor(Color.TRANSPARENT)

        val rendered = ImageEditorRenderer.render(source, ImageEditState())
        assertNotNull(rendered)
        val output = track(requireNotNull(rendered))

        assertTrue(output.hasAlpha())
        assertEquals(0, Color.alpha(output.getPixel(0, 0)))
    }

    @Test
    fun hostileDimensionsAreRejectedWithoutOverflow() {
        assertFalse(withinImageEditorPixelLimit(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(withinImageEditorPixelLimit(0, 100))
        assertFalse(withinImageEditorPixelLimit(3_000, 1_000))
        assertTrue(withinImageEditorPixelLimit(2_048, 2_048))
    }

    private fun bitmap(
        width: Int,
        height: Int,
        color: Int,
    ): Bitmap = track(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })

    private fun track(bitmap: Bitmap): Bitmap = bitmap.also(bitmaps::add)
}
