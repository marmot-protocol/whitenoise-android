package dev.ipf.whitenoise.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Batched pointer events exercise the avatar's real image transform before owner recomposition. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AvatarViewerGestureTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun batchedPanRetainsEveryDeltaBeforeTheNextFrame() {
        val source = Bitmap.createBitmap(SOURCE_EDGE, SOURCE_EDGE, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        Canvas(source).drawRect(
            SOURCE_EDGE / 2f - STRIPE_HALF_WIDTH,
            0f,
            SOURCE_EDGE / 2f + STRIPE_HALF_WIDTH,
            SOURCE_EDGE.toFloat(),
            Paint().apply { color = Color.RED },
        )
        val image = source.asImageBitmap()
        rule.setContent {
            WhiteNoiseTheme {
                AvatarFullScreenViewer("Gesture picture", "gesture-picture", picture = image, onDismiss = {})
            }
        }
        val dialog = rule.onNode(isDialog())
        dialog.performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        val before = stripeCenter()
        rule.mainClock.autoAdvance = false
        try {
            dialog.performTouchInput {
                down(center)
                for (step in 1..PAN_MOVES) {
                    updatePointerTo(0, center + Offset(PAN_STEP * step, 0f))
                    move(delayMillis = 0L)
                }
                up()
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        assertEquals(PAN_STEP * PAN_MOVES, stripeCenter() - before, 2f)
    }

    @Test
    fun doubleTapResetsAZoomedAvatarWithoutStationaryPanInterference() {
        val source = Bitmap.createBitmap(SOURCE_EDGE, SOURCE_EDGE, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        Canvas(source).drawRect(
            SOURCE_EDGE / 2f - STRIPE_HALF_WIDTH,
            0f,
            SOURCE_EDGE / 2f + STRIPE_HALF_WIDTH,
            SOURCE_EDGE.toFloat(),
            Paint().apply { color = Color.RED },
        )
        rule.setContent {
            WhiteNoiseTheme {
                AvatarFullScreenViewer(
                    "Gesture picture",
                    "gesture-picture",
                    picture = source.asImageBitmap(),
                    onDismiss = {},
                )
            }
        }
        val dialog = rule.onNode(isDialog())
        rule.waitForIdle()
        val fittedWidth = stripeWidth()
        dialog.performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertTrue(stripeWidth() > fittedWidth * 2)
        dialog.performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(fittedWidth.toFloat(), stripeWidth().toFloat(), 2f)
        dialog.performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertTrue(stripeWidth() > fittedWidth * 2)
        dialog.performTouchInput {
            down(center)
            up()
            advanceEventTime(60L)
            down(center)
            moveTo(center + Offset(1f, 1f), delayMillis = 10L)
            up()
        }
        rule.waitForIdle()
        assertEquals(fittedWidth.toFloat(), stripeWidth().toFloat(), 2f)
    }

    private fun stripeWidth(): Int {
        val pixels = rule.onNode(isDialog()).captureToImage().toPixelMap()
        return (0 until pixels.width).count { x ->
            val pixel = pixels[x, pixels.height / 2]
            pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f
        }
    }

    /** A narrow source stripe provides an independent pixel position, not another copy of transform state. */
    private fun stripeCenter(): Float {
        val pixels = rule.onNode(isDialog()).captureToImage().toPixelMap()
        val red =
            (0 until pixels.width).filter { x ->
                val pixel = pixels[x, pixels.height / 2]
                pixel.red > 0.8f && pixel.green < 0.2f && pixel.blue < 0.2f
            }
        check(red.isNotEmpty()) { "The source stripe must remain inside the avatar viewport" }
        return (red.first() + red.last()) / 2f
    }

    private companion object {
        const val SOURCE_EDGE = 1000
        const val STRIPE_HALF_WIDTH = 30f
        const val PAN_STEP = 20f
        const val PAN_MOVES = 3
    }
}
