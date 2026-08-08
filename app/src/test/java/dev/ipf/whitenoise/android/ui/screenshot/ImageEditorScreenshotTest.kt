package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.media.ImageEditorContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ImageEditorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun photoEditorCropLight() {
        val bitmap = editorSourceBitmap()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    ImageEditorContent(
                        source = bitmap,
                        onCancel = {},
                        onSave = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("1:1").performClick()
        composeRule.waitForIdle()

        composeRule
            .onRoot()
            .captureRoboImage("src/test/snapshots/image_editor_crop_light.png")
    }

    private fun editorSourceBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(32, 71, 111)
        canvas.drawRect(0f, 0f, 240f, 180f, paint)
        paint.color = Color.rgb(226, 172, 84)
        canvas.drawCircle(186f, 48f, 34f, paint)
        paint.color = Color.rgb(53, 126, 82)
        canvas.drawRect(0f, 118f, 240f, 180f, paint)
        paint.color = Color.rgb(224, 235, 242)
        canvas.drawRect(20f, 82f, 112f, 132f, paint)
        return bitmap
    }
}
