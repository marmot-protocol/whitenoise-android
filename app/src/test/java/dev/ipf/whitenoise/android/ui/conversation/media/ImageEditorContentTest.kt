package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
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
class ImageEditorContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    @After
    fun recycleSource() {
        if (!source.isRecycled) source.recycle()
    }

    @Test
    fun toolsExposeTalkBackLabelsAndUndoRedoState() {
        render()

        composeRule.onNodeWithContentDescription(string(R.string.image_editor_crop)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.image_editor_draw)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.image_editor_rotate_left)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.image_editor_rotate_right)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.image_editor_undo)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.image_editor_redo)).assertIsNotEnabled()

        composeRule.onNodeWithContentDescription(string(R.string.image_editor_rotate_right)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.image_editor_undo)).assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription(string(R.string.image_editor_redo)).assertIsEnabled()
    }

    @Test
    fun resetRestoresTheUneditedStateBeforeSave() {
        var saved: ImageEditState? = null
        render(onSave = { saved = it })

        composeRule.onNodeWithContentDescription(string(R.string.image_editor_rotate_right)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.image_editor_reset)).performClick()
        composeRule.onNodeWithText(string(R.string.save)).performClick()

        composeRule.runOnIdle { assertEquals(ImageEditState(), saved) }
    }

    @Test
    fun cropOffersFreeAndCommonAspectPresets() {
        render()

        composeRule.onNodeWithContentDescription(string(R.string.image_editor_crop)).performClick()
        composeRule.onNodeWithText(string(R.string.image_editor_aspect_free)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.image_editor_aspect_square)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.image_editor_aspect_four_three)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.image_editor_aspect_sixteen_nine)).assertIsDisplayed()
    }

    @Test
    fun cancelReturnsWithoutSaving() {
        var cancelled = false
        var saveCount = 0
        render(
            onCancel = { cancelled = true },
            onSave = { saveCount++ },
        )

        composeRule.onNodeWithContentDescription(string(R.string.cancel)).performClick()

        composeRule.runOnIdle {
            assertTrue(cancelled)
            assertEquals(0, saveCount)
        }
    }

    @Test
    fun primaryActionsRemainReachableAtLargeFontScale() {
        render(fontScale = 2f)

        composeRule.onNodeWithContentDescription(string(R.string.cancel)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.save)).assertIsDisplayed()
    }

    @Test
    fun editorCanvasExcludesSystemGestures() {
        render()

        composeRule
            .onNode(SemanticsMatcher.expectValue(ImageEditorGestureExclusionKey, true))
            .assertIsDisplayed()
    }

    private fun render(
        fontScale: Float = 1f,
        onCancel: () -> Unit = {},
        onSave: (ImageEditState) -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WhiteNoiseTheme(darkTheme = true) {
                    ImageEditorContent(
                        source = source,
                        onCancel = onCancel,
                        onSave = onSave,
                    )
                }
            }
        }
    }

    private fun string(resId: Int): String = app.getString(resId)
}
