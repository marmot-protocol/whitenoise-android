package dev.ipf.whitenoise.android.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.media.IdentityImageCropShape
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventImagePane
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual app windows with synthetic pixels; no network, account or file mutation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class FullscreenWindowMotionCaptureTest {
    @get:Rule val rule = createComposeRule()

    @Test fun captureCircleCropClose() {
        captureCrop(IdentityImageCropShape.Circle, "identity-circle-crop", "identity_crop.cancel")
    }

    @Test fun captureGroupCropDone() {
        captureCrop(IdentityImageCropShape.RoundedSquare, "identity-group-crop", "identity_crop.confirm")
    }

    private fun captureCrop(
        shape: IdentityImageCropShape,
        name: String,
        closeTag: String,
    ) {
        assumeCapture()
        val image = image().asImageBitmap()
        val shown = mutableStateOf(false)
        render {
            if (shown.value) {
                IdentityImageCropDialog(image, EditorPixelSize(240, 160), shape, { shown.value = false }, {
                    shown.value =
                        false
                })
            }
        }
        capture("$name/forward") { rule.runOnIdle { shown.value = true } }
        capture("$name/reverse") { rule.onNodeWithTag(closeTag).performClick() }
    }

    @Test fun captureNostrFullscreenClose() {
        assumeCapture()
        val image = image().asImageBitmap()
        val shown = mutableStateOf(false)
        render {
            if (shown.value) {
                NostrEventImagePane(
                    url = "https://images.example/synthetic-motion",
                    initiallyRequested = true,
                    onFullscreenDismiss = { shown.value = false },
                    loadImage = { _, _ -> image },
                )
            }
        }
        capture("nostr-fullscreen/forward") { rule.runOnIdle { shown.value = true } }
        capture("nostr-fullscreen/reverse") { rule.onNodeWithContentDescription("Close").performClick() }
    }

    private fun render(content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun capture(
        name: String,
        action: () -> Unit,
    ) {
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        rule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(32) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }

    private fun image(): Bitmap =
        Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) for (y in 0 until height) setPixel(x, y, android.graphics.Color.rgb(x, y, 160))
        }

    private fun assumeCapture() = assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
}
