package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoCropPreset
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorScreen
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorStateHolder
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorTool
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun budgetRefusalLight() = captureBudgetRefusal(dark = false, suffix = "light")

    @Test
    fun budgetRefusalDark() = captureBudgetRefusal(dark = true, suffix = "dark")

    @Test
    fun tightLargePhotoCropLight() = captureLargeCrop(dark = false, suffix = "light")

    @Test
    fun tightLargePhotoCropDark() = captureLargeCrop(dark = true, suffix = "dark")

    @Test
    fun photoFirstLight() = capturePhotoFirst("light")

    @Test
    fun photoFirstDark() = capturePhotoFirst("dark")

    @Test
    fun photoFirstAmoled() = capturePhotoFirst("amoled")

    @Test
    fun photoFirstDrawOptions() = capturePhotoFirst("amoled", draw = true)

    @Test
    fun photoFirstRtlLargeText() = capturePhotoFirst("amoled", rtl = true, fontScale = 2f)

    @Test
    fun photoFirstSquare() = capturePhotoFirst("amoled", photoHeight = 240)

    @Test
    fun photoFirstWide() = capturePhotoFirst("amoled", photoHeight = 135)

    private fun capturePhotoFirst(
        theme: String,
        draw: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        photoHeight: Int = 320,
    ) {
        val suffix =
            when {
                draw -> "draw"
                rtl -> "rtl_large"
                photoHeight == 240 -> "square"
                photoHeight == 135 -> "wide"
                else -> "crop"
            }
        val bitmap = editorSourceBitmap(height = photoHeight)
        val size = EditorPixelSize(bitmap.width, bitmap.height)
        val holder = PhotoEditorStateHolder(PhotoEditRecipe.Original, MediaQuality.Standard, size)
        if (draw) holder.selectTool(PhotoEditorTool.Draw)
        try {
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WhiteNoiseTheme(darkTheme = theme != "light", amoled = theme == "amoled") {
                        PhotoEditorScreen(
                            bitmap,
                            PhotoEditorSourceInfo(size, size, 1, "image/png", false),
                            holder,
                            {},
                            { _, _ -> },
                        )
                    }
                }
            }
            composeRule.onRoot().captureRoboImage("src/test/snapshots/photo_editor_${theme}_$suffix.png")
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureLargeCrop(
        dark: Boolean,
        suffix: String,
    ) {
        val preview = editorSourceBitmap()
        val size = EditorPixelSize(12000, 9000)
        val recipe =
            PhotoEditRecipe(
                crop =
                    dev.ipf.whitenoise.android.media.editor
                        .NormalizedRect(0.1f, 0.1f, 0.2f, 0.3f),
            )
        val holder = PhotoEditorStateHolder(recipe, MediaQuality.High, size)
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = dark) {
                    PhotoEditorScreen(
                        previewBitmap = preview,
                        sourceInfo = PhotoEditorSourceInfo(size, size, 1, "image/jpeg", false),
                        stateHolder = holder,
                        onCancel = {},
                        onSave = { _, _ -> },
                    )
                }
            }
            composeRule.onRoot().captureRoboImage("src/test/snapshots/photo_editor_large_region_crop_$suffix.png")
        } finally {
            preview.recycle()
        }
    }

    private fun captureBudgetRefusal(
        dark: Boolean,
        suffix: String,
    ) {
        val bitmap = editorSourceBitmap()
        val size = EditorPixelSize(bitmap.width, bitmap.height)
        val holder = PhotoEditorStateHolder(PhotoEditRecipe.Original, MediaQuality.High, size)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        holder.beginSaving()
        holder.finishSaving(context.getString(R.string.photo_editor_budget_exceeded))
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = dark) {
                    PhotoEditorScreen(
                        previewBitmap = bitmap,
                        sourceInfo = PhotoEditorSourceInfo(size, size, 1, "image/png", false),
                        stateHolder = holder,
                        onCancel = {},
                        onSave = { _, _ -> },
                    )
                }
            }
            composeRule.onNodeWithText(context.getString(R.string.photo_editor_budget_exceeded)).assertIsDisplayed()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/photo_editor_budget_refusal_$suffix.png")
        } finally {
            bitmap.recycle()
        }
    }

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

    /** Narrow short window keeps canvas and scrollable commands reachable. */
    @Test
    @Config(qualifiers = "w240dp-h320dp-mdpi")
    fun narrowShortWindowKeepsCanvasAndScrollableCommandsReachable() {
        val bitmap = editorSourceBitmap()
        val size = EditorPixelSize(bitmap.width, bitmap.height)
        val holder = PhotoEditorStateHolder(PhotoEditRecipe.Original, MediaQuality.Standard, size)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) {
                    PhotoEditorScreen(
                        previewBitmap = bitmap,
                        sourceInfo = PhotoEditorSourceInfo(size, size, 1, "image/png", false),
                        stateHolder = holder,
                        onCancel = {},
                        onSave = { _, _ -> },
                    )
                }
            }
            val canvas =
                composeRule
                    .onNodeWithContentDescription(
                        context.getString(R.string.photo_editor_image_description),
                    ).fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(canvas.width > 0f && canvas.height > 0f)
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.photo_editor_draw))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            assertEquals(PhotoEditorTool.Draw, holder.state.activeTool)
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.photo_editor_width_extra_large))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            assertEquals(
                dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoStrokeWidth.ExtraLarge,
                holder.state.strokeWidth,
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Light landscape retains labeled tools and native history. */
    @Test
    @Config(qualifiers = "w840dp-h400dp-mdpi")
    fun lightLandscapeRetainsLabeledToolsAndNativeHistory() {
        val bitmap = editorSourceBitmap()
        val size = EditorPixelSize(bitmap.width, bitmap.height)
        val holder = PhotoEditorStateHolder(PhotoEditRecipe.Original, MediaQuality.Standard, size)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) {
                    PhotoEditorScreen(
                        previewBitmap = bitmap,
                        sourceInfo = PhotoEditorSourceInfo(size, size, 1, "image/png", false),
                        stateHolder = holder,
                        onCancel = {},
                        onSave = { _, _ -> },
                    )
                }
            }
            composeRule.onNodeWithText(context.getString(R.string.photo_editor_title)).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(context.getString(R.string.save)).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(context.getString(R.string.photo_editor_draw)).performClick()
            assertEquals(PhotoEditorTool.Draw, holder.state.activeTool)
            composeRule.onNodeWithContentDescription(context.getString(R.string.photo_editor_crop)).performClick()
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.photo_editor_rotate_clockwise))
                .performClick()
            assertEquals(1, holder.state.recipe.quarterTurnsClockwise)
            composeRule.onNodeWithContentDescription(context.getString(R.string.photo_editor_undo)).performClick()
            assertEquals(PhotoEditRecipe.Original, holder.state.recipe)
            composeRule.waitForIdle()
            composeRule.onRoot().captureRoboImage("src/test/snapshots/photo_editor_light_landscape.png")
        } finally {
            bitmap.recycle()
        }
    }

    private fun editorSourceBitmap(height: Int = 180): Bitmap {
        val bitmap = Bitmap.createBitmap(240, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(32, 71, 111)
        canvas.drawRect(0f, 0f, 240f, height.toFloat(), paint)
        paint.color = Color.rgb(226, 172, 84)
        canvas.drawCircle(186f, 48f, 34f, paint)
        paint.color = Color.rgb(53, 126, 82)
        canvas.drawRect(0f, height * (118f / 180f), 240f, height.toFloat(), paint)
        paint.color = Color.rgb(224, 235, 242)
        canvas.drawRect(20f, height * (82f / 180f), 112f, height * (132f / 180f), paint)
        return bitmap
    }
}
