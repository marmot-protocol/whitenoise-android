package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoCropPreset
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorScreen
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorStateHolder
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorTool
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
class PhotoEditorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun photoEditorSquareCrop() {
        val bitmap = editorSourceBitmap()
        val source =
            PhotoEditorSourceInfo(
                encodedSize = EditorPixelSize(bitmap.width, bitmap.height),
                orientedSize = EditorPixelSize(bitmap.width, bitmap.height),
                exifOrientation = 1,
                mediaType = "image/png",
                mayHaveAlpha = false,
            )
        val holder =
            PhotoEditorStateHolder(
                initialRecipe = PhotoEditRecipe.Original,
                initialQuality = MediaQuality.Standard,
                orientedSize = source.orientedSize,
            )
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = true) {
                    PhotoEditorScreen(
                        previewBitmap = bitmap,
                        sourceInfo = source,
                        stateHolder = holder,
                        onCancel = {},
                        onSave = { _, _ -> },
                    )
                }
            }
            composeRule.onRoot().captureRoboImage("src/test/snapshots/photo_editor_overview.png")
            // Visual snapshots use settled state; synthetic clicks can leave runner-dependent press ripples.
            composeRule.runOnIdle { holder.selectCropPreset(PhotoCropPreset.Square) }
            composeRule.waitForIdle()

            composeRule.onRoot().captureRoboImage("src/test/snapshots/photo_editor_square_crop.png")
            composeRule.runOnIdle { holder.selectTool(PhotoEditorTool.Draw) }
            composeRule.waitForIdle()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/photo_editor_cropped_draw.png")
        } finally {
            bitmap.recycle()
        }
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
