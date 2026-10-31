package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual production Canvas path: fit, black bars and asymmetric quadrant orientation after rotation.
 * Raw video has no text, theme, font-scale or RTL-dependent geometry. These goldens do not qualify HDR decoding.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RawVideoOutputScreenshotTest {
    @Test
    fun landscapeFrameFitsLandscapeSurfaceWithBlackLetterbox() {
        capture(
            320,
            240,
            0,
            Rect(0, 40, 320, 200),
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            "raw_video_landscape_letterbox",
        )
    }

    @Test
    fun landscapeFrameFitsPortraitSurfaceWithoutStretching() {
        capture(
            240,
            320,
            0,
            Rect(0, 100, 240, 220),
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            "raw_video_portrait_letterbox",
        )
    }

    @Test
    fun clockwiseQuarterTurnFitsPortraitSurfaceWithBlackPillarbox() {
        capture(
            240,
            320,
            90,
            Rect(40, 0, 200, 320),
            intArrayOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            "raw_video_portrait_rotation_90",
        )
    }

    @Test
    fun counterClockwiseQuarterTurnFitsLandscapeSurfaceWithBlackPillarbox() {
        capture(
            320,
            240,
            270,
            Rect(100, 0, 220, 240),
            intArrayOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
            "raw_video_landscape_rotation_270",
        )
    }

    @Suppress("LongParameterList") // Independent expected geometry and colors for each renderer case.
    private fun capture(
        width: Int,
        height: Int,
        rotation: Int,
        content: Rect,
        colors: IntArray,
        name: String,
    ) {
        val source = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val fixture = Canvas(source)
            val solid = Paint()
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { index, color ->
                solid.color = color
                val left = (index % 2) * 80f
                val top = (index / 2) * 40f
                fixture.drawRect(left, top, left + 80f, top + 40f, solid)
            }
            // Nonblack poison makes a missing clear visible, including every letterbox/pillarbox pixel.
            output.eraseColor(Color.MAGENTA)
            val canvas = Canvas(output)
            val initialSaveCount = canvas.saveCount
            drawRawVideoFrame(canvas, source, rotation, Paint(Paint.FILTER_BITMAP_FLAG))
            assertEquals(initialSaveCount, canvas.saveCount)
            assertPixels(output, content, colors)
            output.captureRoboImage("src/test/snapshots/$name.png")
        } finally {
            source.recycle()
            output.recycle()
        }
    }

    private fun assertPixels(
        bitmap: Bitmap,
        content: Rect,
        colors: IntArray,
    ) {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (!content.contains(x, y)) {
                    assertEquals("Black outside content at $x,$y", Color.BLACK, bitmap.getPixel(x, y))
                }
            }
        }
        colors.forEachIndexed { index, color ->
            val x = content.left + content.width() * (if (index % 2 == 0) 1 else 3) / 4
            val y = content.top + content.height() * (if (index / 2 == 0) 1 else 3) / 4
            assertEquals("Rotated quadrant $index", color, bitmap.getPixel(x, y))
        }
    }
}
