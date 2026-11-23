package dev.ipf.whitenoise.android.notifications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Guards the status-bar small icons' occupied area, clearance and tint-only artwork. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "xxxhdpi")
class NotificationSmallIconScreenshotTest {
    /**
     * Shows tint and geometry at representative slot sizes with a shared xxxhdpi source.
     * This sheet does not emulate SystemUI or compare device-density filtering.
     */
    @Test
    fun smallIconsAtStatusBarSizes() {
        val icons = listOf(R.drawable.ic_stat_whitenoise, R.drawable.ic_stat_dictation)
        val cell = SNAPSHOT_SIZES.max() + SNAPSHOT_PADDING_PX * 2
        val sheet = Bitmap.createBitmap(cell * SNAPSHOT_SIZES.size * 2, cell * icons.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        icons.forEachIndexed { row, icon ->
            listOf(Color.BLACK to Color.WHITE, Color.WHITE to Color.BLACK).forEachIndexed { theme, (surface, tint) ->
                SNAPSHOT_SIZES.forEachIndexed { column, size ->
                    val left = (theme * SNAPSHOT_SIZES.size + column) * cell
                    val top = row * cell
                    canvas.save()
                    canvas.clipRect(left, top, left + cell, top + cell)
                    canvas.drawColor(surface)
                    canvas.restore()
                    val inset = (cell - size) / 2
                    canvas.drawBitmap(render(icon, size, tint), (left + inset).toFloat(), (top + inset).toFloat(), null)
                }
            }
        }
        sheet.captureRoboImage("src/test/snapshots/notification_small_icons.png")
    }

    /** Requires the message mark to fill most of its canvas while keeping clearance at every edge. */
    @Test
    fun messageMarkUsesMostOfTheCanvasWithClearance() {
        val bounds = alphaBounds(render(R.drawable.ic_stat_whitenoise, MEASURE_SIZE_PX, Color.WHITE))
        assertTrue("Mark width ${bounds.width} px is too narrow", bounds.width >= MEASURE_SIZE_PX * 0.85)
        assertTrue("Mark height ${bounds.height} px is too short", bounds.height >= MEASURE_SIZE_PX * 0.6)
        assertClearance(bounds)
    }

    /** Keeps the dictation icon's logo and microphone inside the canvas. */
    @Test
    fun dictationIconKeepsClearance() {
        assertClearance(alphaBounds(render(R.drawable.ic_stat_dictation, MEASURE_SIZE_PX, Color.WHITE)))
    }

    /**
     * Keeps the dictation logo the same size and position as the message mark: outside the
     * bottom-right corner that holds the microphone and its gap, both icons must cover the same pixels.
     * The dictation clip path can shift antialiased edges by a few alpha levels; a one-pixel move or
     * a resize changes edge pixels by far more.
     */
    @Test
    fun dictationLogoMatchesMessageMark() {
        val message = render(R.drawable.ic_stat_whitenoise, MEASURE_SIZE_PX, Color.WHITE)
        val dictation = render(R.drawable.ic_stat_dictation, MEASURE_SIZE_PX, Color.WHITE)
        val micCorner = (MEASURE_SIZE_PX * MIC_CORNER_START).toInt()
        for (y in 0 until MEASURE_SIZE_PX) {
            for (x in 0 until MEASURE_SIZE_PX) {
                if (x >= micCorner && y >= micCorner) continue
                val expected = Color.alpha(message.getPixel(x, y))
                val actual = Color.alpha(dictation.getPixel(x, y))
                val matches = abs(actual - expected) <= EDGE_ALPHA_TOLERANCE
                assertTrue("Pixel ($x, $y) alpha $actual, expected $expected", matches)
            }
        }
    }

    /** The system tints small icons from alpha, so any visible pixel must be plain white. */
    @Test
    fun smallIconsAreAlphaOnlyWhite() {
        for (icon in listOf(R.drawable.ic_stat_whitenoise, R.drawable.ic_stat_dictation)) {
            val bitmap = render(icon, MEASURE_SIZE_PX, tint = null)
            for (y in 0 until MEASURE_SIZE_PX) {
                for (x in 0 until MEASURE_SIZE_PX) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) == 0) continue
                    val darkest = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    assertTrue("Pixel ($x, $y) is #${Integer.toHexString(pixel)}", darkest >= WHITE_MIN_CHANNEL)
                }
            }
        }
    }

    private fun render(
        icon: Int,
        size: Int,
        tint: Int?,
    ): Bitmap {
        val drawable = checkNotNull(RuntimeEnvironment.getApplication().getDrawable(icon)).mutate()
        tint?.let { drawable.colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
        drawable.setBounds(0, 0, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
    }

    private fun alphaBounds(bitmap: Bitmap): PixelBounds {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y).ushr(24) != 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return PixelBounds(left, top, right, bottom)
    }

    private fun assertClearance(bounds: PixelBounds) {
        val edges =
            listOf(
                bounds.left,
                bounds.top,
                MEASURE_SIZE_PX - 1 - bounds.right,
                MEASURE_SIZE_PX - 1 - bounds.bottom,
            )
        assertTrue("Edge clearance $edges px is under $MIN_CLEARANCE_PX px", edges.all { it >= MIN_CLEARANCE_PX })
    }

    private data class PixelBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    private companion object {
        /**
         * The status bar draws small icons at about 17dp: pixel sizes at 1.5x, 2x and a current Pixel's
         * 2.625x density.
         */
        val SNAPSHOT_SIZES = listOf(26, 34, 45)
        const val SNAPSHOT_PADDING_PX = 8

        /** 24dp at xxxhdpi, where one dp is four pixels. */
        const val MEASURE_SIZE_PX = 96
        const val MIN_CLEARANCE_PX = 4

        /** The microphone and its gap stay right of and below this fraction of the canvas. */
        const val MIC_CORNER_START = 0.45f
        const val EDGE_ALPHA_TOLERANCE = 8

        /** Allows unpremultiply rounding on antialiased edges. */
        const val WHITE_MIN_CHANNEL = 250
    }
}
