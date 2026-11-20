package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.media.CameraMode
import dev.ipf.whitenoise.android.ui.conversation.media.ConversationCameraContent
import dev.ipf.whitenoise.android.ui.conversation.media.ConversationCameraUiState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The in-app camera's controls over a stand-in preview: Photo, Video, recording, and RTL at a large font. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ConversationCameraScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Photo mode, as the camera opens. */
    @Test
    fun cameraPhotoMode() = capture("photo", ConversationCameraUiState())

    /** Video mode before recording, with microphone access refused. */
    @Test
    fun cameraVideoModeWithoutSound() {
        capture("video_no_sound", ConversationCameraUiState(mode = CameraMode.Video, videoWithoutSound = true))
    }

    /** A recording in progress: timer, stop shutter, no mode switch. */
    @Test
    fun cameraRecording() {
        val recording = ConversationCameraUiState(mode = CameraMode.Video, recording = true, recordedSeconds = 75)
        capture("recording", recording)
    }

    /** Photo mode right to left at the largest font scale. */
    @Test
    fun cameraPhotoModeLargeRtl() = capture("photo_large_rtl", ConversationCameraUiState(), fontScale = 2f, rtl = true)

    /** Renders the controls over a gradient standing in for the camera preview and captures them as [name]. */
    private fun capture(
        name: String,
        state: ConversationCameraUiState,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = true, fontScale = fontScale) {
                    ConversationCameraContent(
                        state = state,
                        onClose = {},
                        onModeChange = {},
                        onShutter = {},
                        onSwitchCamera = {},
                        preview = {
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(listOf(Color(0xFF8D7B68), Color(0xFF3E3A36))),
                                ),
                            )
                        },
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_camera_$name.png")
    }
}
