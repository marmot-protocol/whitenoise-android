package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorScreen
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorStateHolder
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

/** Output quality is chosen inside the photo editor now, and the choice rides the editor's own save. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class PhotoEditorQualityControlTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var bitmap: Bitmap
    private lateinit var holder: PhotoEditorStateHolder

    /** Builds one editor session over a small in-memory photo. */
    @Before
    fun setUp() {
        bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        holder =
            PhotoEditorStateHolder(
                initialRecipe = PhotoEditRecipe.Original,
                initialQuality = MediaQuality.Standard,
                orientedSize = EditorPixelSize(64, 48),
            )
    }

    /** Releases the preview bitmap between cases. */
    @After
    fun tearDown() {
        bitmap.recycle()
    }

    /** The controls panel offers every level and the chosen one reaches the save callback. */
    @Test
    fun qualityButtonOpensEveryLevelAndCarriesTheChoiceIntoSave() {
        var saved: Pair<PhotoEditRecipe, MediaQuality>? = null
        render(onSave = { recipe, quality -> saved = recipe to quality })

        composeRule
            .onNodeWithText(
                string(R.string.photo_editor_quality) + ": " + string(R.string.photo_editor_quality_standard),
            ).assertIsDisplayed()
        composeRule.onNodeWithTag("photo.editor.quality").performClick()
        composeRule.onNodeWithText(string(R.string.photo_editor_quality_explanation)).assertIsDisplayed()
        listOf(
            R.string.photo_editor_quality_low,
            R.string.photo_editor_quality_standard,
            R.string.photo_editor_quality_high,
            R.string.photo_editor_quality_original,
            // "Original" is also a crop preset behind the dialog, so match any displayed node.
        ).forEach { level ->
            assertTrue(
                "quality level ${string(level)} is offered",
                composeRule.onAllNodesWithText(string(level)).fetchSemanticsNodes().isNotEmpty(),
            )
        }

        composeRule.onNodeWithText(string(R.string.photo_editor_quality_high)).performClick()
        assertEquals(MediaQuality.High, holder.state.quality)

        composeRule.onNodeWithContentDescription(string(R.string.save)).performClick()
        assertEquals(MediaQuality.High, saved?.second)
    }

    /** The coordinate dialog is reachable from the controls panel for the active tool. */
    @Test
    fun coordinateDialogIsReachableForTheActiveTool() {
        render()

        composeRule.onNodeWithContentDescription(string(R.string.photo_editor_adjust_crop)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.photo_editor_left)).assertIsDisplayed()
        composeRule.onNodeWithTag("photo.editor.coordinates.apply").assertIsDisplayed()
    }

    private fun render(onSave: (PhotoEditRecipe, MediaQuality) -> Unit = { _, _ -> }) {
        composeRule.setContent {
            WhiteNoiseTheme {
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
                    onCancel = {},
                    onSave = onSave,
                )
            }
        }
    }

    private fun string(
        resource: Int,
        vararg arguments: Any,
    ): String = app.getString(resource, *arguments)
}
