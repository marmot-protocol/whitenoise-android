package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.media.IdentityImageCropShape
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.ui.common.IdentityImageCropDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the crop mask shows before an identity image is published.
 *
 * The mask is the promise the surface makes: whatever it frames is what gets uploaded. These record
 * both shapes against a source with an off-centre landmark, so a mask that drifts, stretches or
 * stops clipping is visible rather than something only the published bytes would reveal.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class IdentityImageCropScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** An avatar crop frames the picture in a circle. */
    @Test
    fun avatarCropCircleLight() = capture(IdentityImageCropShape.Circle, "identity_crop_circle_light")

    /** A group image crop frames the picture in a rounded square. */
    @Test
    fun groupCropSquareLight() = capture(IdentityImageCropShape.RoundedSquare, "identity_crop_rounded_square_light")

    /** AMOLED keeps the mask and its actions legible against the monochrome surface. */
    @Test
    fun avatarCropCircleAmoled() = capture(IdentityImageCropShape.Circle, "identity_crop_circle_amoled", amoled = true)

    /** Renders the crop surface over a fixed source and records its baseline. */
    private fun capture(
        shape: IdentityImageCropShape,
        name: String,
        amoled: Boolean = false,
    ) {
        val preview = landmarkSource()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = amoled, amoled = amoled) {
                IdentityImageCropDialog(
                    preview = preview.asImageBitmap(),
                    sourceSize = EditorPixelSize(preview.width, preview.height),
                    shape = shape,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(ADVANCE_MILLIS)
        composeRule.onNodeWithTag("identity_crop.dialog").captureRoboImage("src/test/snapshots/$name.png")
    }

    /** A landscape source with a bright block off to one side, so any drift in the mask shows. */
    private fun landmarkSource(): Bitmap {
        val bitmap = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
        for (x in 0 until SOURCE_WIDTH) {
            for (y in 0 until SOURCE_HEIGHT) {
                val inLandmark = x < SOURCE_WIDTH / 4 && y < SOURCE_HEIGHT / 2
                bitmap.setPixel(x, y, if (inLandmark) Color.YELLOW else Color.rgb(x % 256, 90, 160))
            }
        }
        return bitmap
    }

    private companion object {
        const val SOURCE_WIDTH = 240
        const val SOURCE_HEIGHT = 120
        const val ADVANCE_MILLIS = 500L
    }
}
