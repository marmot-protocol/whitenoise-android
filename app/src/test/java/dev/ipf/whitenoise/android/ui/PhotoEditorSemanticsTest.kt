package dev.ipf.whitenoise.android.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.NormalizedPoint
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorScreen
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorStateHolder
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorTool
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class PhotoEditorSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var bitmap: Bitmap
    private lateinit var holder: PhotoEditorStateHolder

    @Before
    fun setUp() {
        bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        holder =
            PhotoEditorStateHolder(
                initialRecipe = PhotoEditRecipe.Original,
                initialQuality = MediaQuality.Standard,
                orientedSize = EditorPixelSize(64, 48),
                newStrokeId = { "stroke" },
            )
    }

    @After
    fun tearDown() {
        bitmap.recycle()
    }

    @Test
    fun toolsHistoryAndQualityExposeSelectedAndEnabledState() {
        render()

        composeRule.onNodeWithText(string(R.string.photo_editor_crop)).assertIsSelected()
        composeRule.onNodeWithText(string(R.string.photo_editor_draw)).performClick().assertIsSelected()
        composeRule
            .onNodeWithContentDescription(
                string(
                    R.string.photo_editor_color_selected,
                    string(R.string.photo_editor_color_red),
                ),
            ).assertIsSelected()
        composeRule.onNodeWithContentDescription(string(R.string.photo_editor_color_blue)).performClick()
        composeRule
            .onNodeWithContentDescription(
                string(
                    R.string.photo_editor_color_selected,
                    string(R.string.photo_editor_color_blue),
                ),
            ).assertIsSelected()
        composeRule.onNodeWithContentDescription(string(R.string.photo_editor_undo)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.photo_editor_redo)).assertIsNotEnabled()

        composeRule.runOnIdle {
            holder.commitStroke(listOf(NormalizedPoint(0.2f, 0.2f), NormalizedPoint(0.8f, 0.8f)))
        }
        composeRule.onNodeWithContentDescription(string(R.string.photo_editor_undo)).assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription(string(R.string.photo_editor_redo)).assertIsEnabled()

        composeRule
            .onNodeWithText(string(R.string.photo_editor_quality_high))
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithText(string(R.string.photo_editor_quality_standard)).assertIsNotSelected()
        composeRule
            .onNodeWithText(
                string(
                    R.string.photo_editor_announcement_quality,
                    string(R.string.photo_editor_quality_high),
                ),
            ).assertExists()
        assertEquals(MediaQuality.High, holder.state.quality)
    }

    @Test
    fun cropPresetRotationAndResetAreReachableWithoutGestures() {
        render()

        composeRule
            .onNodeWithText(string(R.string.photo_editor_crop_square))
            .performScrollTo()
            .performClick()
        assertTrue(holder.state.recipe.crop != PhotoEditRecipe.Original.crop)

        composeRule
            .onNodeWithContentDescription(string(R.string.photo_editor_rotate_clockwise))
            .performClick()
        assertEquals(1, holder.state.recipe.quarterTurnsClockwise)

        composeRule
            .onNodeWithContentDescription(string(R.string.photo_editor_reset))
            .performScrollTo()
            .performClick()
        assertEquals(PhotoEditRecipe.Original, holder.state.recipe)
    }

    @Test
    fun labeledActionTargetsMeetFortyEightDpMinimum() {
        render()

        composeRule
            .onNodeWithContentDescription(string(R.string.photo_editor_undo))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule
            .onNodeWithContentDescription(string(R.string.photo_editor_rotate_clockwise))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule
            .onNodeWithText(string(R.string.photo_editor_crop))
            .assertHeightIsAtLeast(48.dp)
        composeRule
            .onNodeWithText(string(R.string.photo_editor_quality_standard))
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun dirtyCancelRequiresExplicitDiscardAndNeverSaves() {
        var cancels = 0
        var saves = 0
        render(onCancel = { cancels += 1 }, onSave = { _, _ -> saves += 1 })
        composeRule.runOnIdle { holder.rotateClockwise() }

        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
        composeRule.onNodeWithText(string(R.string.photo_editor_discard_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.photo_editor_discard)).performClick()

        assertEquals(1, cancels)
        assertEquals(0, saves)
    }

    @Test
    fun savePublishesOneSnapshotAndAnnouncesProgress() {
        var saved: Pair<PhotoEditRecipe, MediaQuality>? = null
        render(onSave = { recipe, quality -> saved = recipe to quality })
        composeRule.runOnIdle {
            holder.selectTool(PhotoEditorTool.Draw)
            holder.commitStroke(listOf(NormalizedPoint(0.5f, 0.5f)))
        }

        composeRule.onNodeWithText(string(R.string.save)).performClick()

        assertEquals(holder.state.recipe, saved?.first)
        assertEquals(MediaQuality.Standard, saved?.second)
        assertTrue(holder.state.isSaving)
        composeRule
            .onNodeWithText(string(R.string.photo_editor_saving))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun largeTextKeepsPrimaryActionsAndToolEscapeVisible() {
        render(fontScale = 2f)

        composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.save)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.photo_editor_crop)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.photo_editor_draw)).assertIsDisplayed()
    }

    @Test
    fun saveFailureIsVisibleAndLeavesEditingEnabled() {
        val failure = string(R.string.photo_editor_save_failed)
        render()
        composeRule.runOnIdle {
            holder.beginSaving()
            holder.finishSaving(failure)
        }

        composeRule.onNodeWithText(failure).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.save)).assertIsEnabled()
    }

    private fun render(
        onCancel: () -> Unit = {},
        onSave: (PhotoEditRecipe, MediaQuality) -> Unit = { _, _ -> },
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                WhiteNoiseTheme(darkTheme = true) {
                    PhotoEditorScreen(
                        previewBitmap = bitmap,
                        sourceInfo =
                            PhotoEditorSourceInfo(
                                encodedSize = EditorPixelSize(64, 48),
                                orientedSize = EditorPixelSize(64, 48),
                                exifOrientation = 1,
                                mediaType = "image/png",
                                mayHaveAlpha = true,
                            ),
                        stateHolder = holder,
                        outputPlan = null,
                        onCancel = onCancel,
                        onSave = onSave,
                    )
                }
            }
        }
    }

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = app.getString(resId, *args)
}
