package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.NormalizedRect
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
        // Keep the 32-source-pixel crop minimum below these pointer-test crop dimensions.
        bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        holder =
            PhotoEditorStateHolder(
                initialRecipe = PhotoEditRecipe.Original,
                initialQuality = MediaQuality.Standard,
                orientedSize = EditorPixelSize(640, 480),
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

    /** Corner selection uses the initial press even when the slop-crossing move is far away. */
    @Test
    fun nearCornerPressResizesInsteadOfMovingTheCropAfterTouchSlop() {
        val crop = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        holder =
            PhotoEditorStateHolder(
                PhotoEditRecipe.Original.copy(crop = crop),
                MediaQuality.Standard,
                EditorPixelSize(640, 480),
            )
        render()
        val canvas = composeRule.onNodeWithContentDescription(string(R.string.photo_editor_image_description))
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val scale = minOf(bounds.width / 64f, bounds.height / 48f)
        val imageLeft = (bounds.width - 64f * scale) / 2f
        val imageTop = (bounds.height - 48f * scale) / 2f
        canvas.performTouchInput {
            // 30dp misses the old 24dp radius; moving another 60dp also leaves the new radius.
            down(Offset(imageLeft + 64f * scale * crop.left + 30f, imageTop + 48f * scale * crop.top))
            moveBy(Offset(0f, 60f))
            up()
        }
        composeRule.runOnIdle {
            val resized = holder.state.recipe.crop
            assertTrue(resized.left > crop.left)
            assertTrue(resized.top > crop.top)
            assertEquals(crop.right, resized.right, 0.001f)
            assertEquals(crop.bottom, resized.bottom, 0.001f)
            holder.undo()
            assertEquals(crop, holder.state.recipe.crop)
        }
    }

    /** Overlapping enlarged corner targets must leave the small crop's center draggable. */
    @Test
    fun smallCropCenterPressMovesWithoutResizing() {
        val crop = NormalizedRect(0.45f, 0.4f, 0.55f, 0.6f)
        holder =
            PhotoEditorStateHolder(
                PhotoEditRecipe.Original.copy(crop = crop),
                MediaQuality.Standard,
                EditorPixelSize(640, 480),
            )
        render()
        val canvas = composeRule.onNodeWithContentDescription(string(R.string.photo_editor_image_description))
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val scale = minOf(bounds.width / 64f, bounds.height / 48f)
        val cornerDistance = kotlin.math.hypot(64f * scale * 0.05f, 48f * scale * 0.1f)
        assertTrue(
            "center misses the old radius but lies inside the enlarged targets",
            cornerDistance > 24f && cornerDistance < 40f,
        )
        canvas.performTouchInput {
            down(Offset(bounds.width / 2f, bounds.height / 2f))
            moveBy(Offset(24f, 30f))
            up()
        }
        composeRule.runOnIdle {
            val moved = holder.state.recipe.crop
            val dx = 24f / (64f * scale)
            val dy = 30f / (48f * scale)
            assertEquals(crop.left + dx, moved.left, 0.001f)
            assertEquals(crop.right + dx, moved.right, 0.001f)
            assertEquals(crop.top + dy, moved.top, 0.001f)
            assertEquals(crop.bottom + dy, moved.bottom, 0.001f)
            assertEquals(crop.width, moved.width, 0.001f)
            assertEquals(crop.height, moved.height, 0.001f)
        }
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
                            encodedSize = EditorPixelSize(640, 480),
                            orientedSize = EditorPixelSize(640, 480),
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
