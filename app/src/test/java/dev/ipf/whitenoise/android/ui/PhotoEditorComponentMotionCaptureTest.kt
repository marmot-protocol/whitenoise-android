package dev.ipf.whitenoise.android.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorDialog
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Component-only capture: no media viewer Edit action or native draft lifecycle is exercised. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class PhotoEditorComponentMotionCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var bitmap: Bitmap? = null

    @After
    fun tearDown() {
        composeRule.mainClock.autoAdvance = true
        bitmap?.recycle()
    }

    @Test
    fun captureComponentOpeningAndCleanToolbarCancel() {
        assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val shown = mutableStateOf(false)
        var cancels = 0
        render(shown = { shown.value }, onCancel = {
            cancels += 1
            shown.value = false
        })
        capture("photo-editor-component/open") {
            composeRule.runOnIdle { shown.value = true }
        }
        composeRule.onNodeWithTag("photo.editor").assertIsDisplayed()
        assertEquals(0, cancels)
        capture("photo-editor-component/clean-toolbar-cancel") {
            composeRule.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        }
        assertEquals(1, cancels)
        composeRule.onNodeWithTag("photo.editor").assertDoesNotExist()
    }

    @Test
    fun captureDirtyToolbarCancelConfirmationAndDiscardExit() {
        assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val shown = mutableStateOf(true)
        var cancels = 0
        render(shown = { shown.value }, onCancel = {
            cancels += 1
            shown.value = false
        })
        composeRule.onNodeWithTag("photo.editor").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(app.getString(R.string.photo_editor_crop)).performClick()
        composeRule.onNodeWithContentDescription(app.getString(R.string.photo_editor_rotate_clockwise)).performClick()
        capture("photo-editor-component/dirty-toolbar-cancel-confirmation") {
            composeRule.onNodeWithContentDescription(app.getString(R.string.close)).performClick()
        }
        assertEquals(0, cancels)
        composeRule.onNodeWithText(app.getString(R.string.photo_editor_discard_title)).assertIsDisplayed()
        capture("photo-editor-component/discard-exit", frames = 48) {
            composeRule.onNodeWithText(app.getString(R.string.photo_editor_discard)).performClick()
        }
        assertEquals(1, cancels)
        composeRule.onNodeWithTag("photo.editor").assertDoesNotExist()
    }

    private fun render(
        shown: () -> Boolean,
        onCancel: () -> Unit,
    ) {
        val preview =
            Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(54, 110, 132))
            }
        bitmap = preview
        val holder =
            PhotoEditorStateHolder(
                initialRecipe = PhotoEditRecipe.Original,
                initialQuality = MediaQuality.Standard,
                orientedSize = EditorPixelSize(64, 48),
                newStrokeId = { "component-capture-stroke" },
            )
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    // Empty themed host deliberately does not impersonate a viewer or Edit source.
                    Surface(modifier = Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
                    if (shown()) {
                        PhotoEditorDialog(
                            previewBitmap = preview,
                            sourceInfo =
                                PhotoEditorSourceInfo(
                                    encodedSize = EditorPixelSize(64, 48),
                                    orientedSize = EditorPixelSize(64, 48),
                                    exifOrientation = 1,
                                    mediaType = "image/png",
                                    mayHaveAlpha = true,
                                ),
                            stateHolder = holder,
                            onCancel = onCancel,
                            onSave = { _, _, _ -> error("Component capture must never save") },
                        )
                    }
                }
            }
        }
    }

    private fun capture(
        name: String,
        frames: Int = 32,
        action: () -> Unit,
    ) {
        check(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        composeRule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(frames) { frame ->
            composeRule.mainClock.advanceTimeBy(16L)
            composeRule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
        composeRule.mainClock.autoAdvance = true
    }
}
