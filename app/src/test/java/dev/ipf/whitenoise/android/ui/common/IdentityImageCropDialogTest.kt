package dev.ipf.whitenoise.android.ui.common

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import dev.ipf.whitenoise.android.media.IdentityImageCrop
import dev.ipf.whitenoise.android.media.IdentityImageCropShape
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * That the crop surface answers the finger on it.
 *
 * The gesture block outlives recomposition, so it has to read the live crop rather than the value
 * captured when it was built. Reading a stale one looks like a picture that will not move: each
 * drag re-applies its delta to the first crop and the image twitches back. Geometry tests cannot
 * see that, because the arithmetic they exercise is correct either way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class IdentityImageCropDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Dragging the picture moves the crop that gets published. */
    @Test
    fun draggingThePictureMovesTheCropItPublishes() {
        val confirmed = show()

        composeRule.onNodeWithTag("identity_crop.canvas").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("identity_crop.confirm").performClick()

        val crop = requireNotNull(confirmed.value) { "the surface must confirm a crop" }
        // A whole-width drag must carry the crop to its left bound. Asserting merely that it moved
        // would also pass on a stale gesture, where only the final increment of the swipe survives.
        assertTrue(
            "a full drag right must carry the crop to its left bound, but focus was ${crop.focusX}",
            crop.focusX <= LEFT_BOUND_FOCUS,
        )
    }

    /** Turning the picture is carried into the published crop. */
    @Test
    fun rotatingIsCarriedIntoThePublishedCrop() {
        val confirmed = show()

        composeRule.onNodeWithTag("identity_crop.rotate").performClick()
        composeRule.onNodeWithTag("identity_crop.confirm").performClick()

        assertEquals(1, requireNotNull(confirmed.value).quarterTurnsClockwise)
    }

    /** Leaving by the close control publishes nothing. */
    @Test
    fun closingPublishesNothing() {
        val confirmed = show()

        composeRule.onNodeWithTag("identity_crop.canvas").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("identity_crop.cancel").performClick()

        assertNull("a cancelled crop must not publish", confirmed.value)
    }

    /** Renders the surface over a fixed landscape source and captures what it confirms. */
    private fun show(): Captured {
        val captured = Captured()
        val preview = source()
        composeRule.setContent {
            WhiteNoiseTheme {
                IdentityImageCropDialog(
                    preview = preview.asImageBitmap(),
                    sourceSize = EditorPixelSize(preview.width, preview.height),
                    shape = IdentityImageCropShape.Circle,
                    onDismiss = { captured.dismissed = true },
                    onConfirm = { captured.value = it },
                )
            }
        }
        return captured
    }

    private fun source(): Bitmap {
        val bitmap = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
        for (x in 0 until SOURCE_WIDTH) {
            for (y in 0 until SOURCE_HEIGHT) {
                bitmap.setPixel(x, y, if (x < SOURCE_WIDTH / 2) Color.RED else Color.BLUE)
            }
        }
        return bitmap
    }

    /** What the surface handed back, so a test can assert on it after the gesture. */
    private class Captured {
        var value: IdentityImageCrop? = null
        var dismissed: Boolean = false
    }

    private companion object {
        const val SOURCE_WIDTH = 240
        const val SOURCE_HEIGHT = 120

        /** Half the square's width on a 2:1 source, which is as far left as a crop can sit. */
        const val LEFT_BOUND_FOCUS = 0.3f
    }
}
