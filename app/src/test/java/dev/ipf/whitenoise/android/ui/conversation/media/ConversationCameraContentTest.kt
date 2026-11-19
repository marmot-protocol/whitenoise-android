package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The camera opens in Photo mode, switches to Video like a phone camera, and shows a recording's progress. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationCameraContentTest {
    @get:Rule val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var state by mutableStateOf(ConversationCameraUiState())
    private val events = mutableListOf<String>()

    /** Photo is selected at first, and the shutter takes a photo. */
    @Test
    fun opensInPhotoMode() {
        render()

        composeRule.onNodeWithTag("camera.mode.photo").assertIsSelected()
        shutterSays(R.string.camera_take_photo)
        composeRule.onNodeWithTag("camera.shutter").performClick()
        composeRule.runOnIdle { assertEquals(listOf("shutter"), events) }
    }

    /** Choosing Video reports it, and in Video mode the shutter starts a recording. */
    @Test
    fun switchingToVideoStartsRecordingFromTheShutter() {
        render()

        composeRule.onNodeWithTag("camera.mode.video").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("mode:Video"), events)
            state = state.copy(mode = CameraMode.Video)
        }

        composeRule.onNodeWithTag("camera.mode.video").assertIsSelected()
        shutterSays(R.string.camera_start_recording)
    }

    /** While recording, the timer shows, the mode switch and lens button go away, and the shutter stops it. */
    @Test
    fun recordingShowsTheTimerAndStopsFromTheShutter() {
        state = ConversationCameraUiState(mode = CameraMode.Video, recording = true, recordedSeconds = 67)
        render()

        composeRule.onNodeWithText("1:07").assertExists()
        composeRule.onNodeWithTag("camera.modes").assertDoesNotExist()
        composeRule.onNodeWithTag("camera.switch").assertDoesNotExist()
        shutterSays(R.string.camera_stop_recording)
        composeRule.onNodeWithTag("camera.shutter").performClick()
        composeRule.runOnIdle { assertEquals(listOf("shutter"), events) }
    }

    /** The no-sound notice shows only in Video mode, once microphone access was refused. */
    @Test
    fun noSoundNoticeShowsOnlyForVideo() {
        state = ConversationCameraUiState(mode = CameraMode.Photo, videoWithoutSound = true)
        render()
        composeRule.onNodeWithTag("camera.noSound").assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(mode = CameraMode.Video) }

        composeRule.onNodeWithText(context.getString(R.string.camera_video_without_sound)).assertExists()
    }

    /** Close and the lens switch report their own actions. */
    @Test
    fun closeAndSwitchReportTheirActions() {
        render()

        composeRule.onNodeWithTag("camera.switch").performClick()
        composeRule.onNodeWithTag("camera.close").performClick()

        composeRule.runOnIdle { assertEquals(listOf("switch", "close"), events) }
    }

    /** The timer reads minutes and two-digit seconds. */
    @Test
    fun recordingLengthReadsMinutesAndSeconds() {
        assertEquals("0:00", formatRecordingLength(0))
        assertEquals("1:05", formatRecordingLength(65))
        assertEquals("10:00", formatRecordingLength(600))
    }

    /** The shutter's spoken label is [label]. */
    private fun shutterSays(label: Int) {
        composeRule.onNodeWithTag("camera.shutter").assertContentDescriptionEquals(context.getString(label))
    }

    /** Renders the controls over an empty preview, recording each action they report. */
    private fun render() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ConversationCameraContent(
                    state = state,
                    onClose = { events += "close" },
                    onModeChange = { events += "mode:${it.name}" },
                    onShutter = { events += "shutter" },
                    onSwitchCamera = { events += "switch" },
                )
            }
        }
    }
}
