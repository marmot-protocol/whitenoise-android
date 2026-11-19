package dev.ipf.whitenoise.android.ui.conversation.media

import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Capture rotation from how the phone is held, which recordings are kept, and keeping the screen on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationCameraCaptureRulesTest {
    @get:Rule
    val compose = createComposeRule()

    /** A sideways phone gets a sideways capture rotation, rounded to the nearest quarter turn, as CameraX documents. */
    @Test
    fun captureRotationFollowsHowThePhoneIsHeld() {
        val expected =
            mapOf(
                0 to Surface.ROTATION_0,
                44 to Surface.ROTATION_0,
                45 to Surface.ROTATION_270,
                90 to Surface.ROTATION_270,
                134 to Surface.ROTATION_270,
                135 to Surface.ROTATION_180,
                224 to Surface.ROTATION_180,
                225 to Surface.ROTATION_90,
                270 to Surface.ROTATION_90,
                314 to Surface.ROTATION_90,
                315 to Surface.ROTATION_0,
                359 to Surface.ROTATION_0,
            )
        expected.forEach { (degrees, rotation) -> assertEquals("$degrees°", rotation, captureRotationFor(degrees)) }
    }

    /** A phone lying flat reports no angle, so the last known rotation stands. */
    @Test
    fun anUnknownAngleGivesNoRotation() {
        assertNull(captureRotationFor(OrientationEventListener.ORIENTATION_UNKNOWN))
    }

    /** Finished, size- or length-limited, and camera-closed recordings keep their video; failures do not. */
    @Test
    fun onlyRecordingsThatWroteTheirFramesAreUsable() {
        listOf(
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
        ).forEach { assertTrue("error $it", finalizedRecordingIsUsable(it)) }
        listOf(
            VideoRecordEvent.Finalize.ERROR_UNKNOWN,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS,
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED,
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR,
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
            VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED,
        ).forEach { assertFalse("error $it", finalizedRecordingIsUsable(it)) }
    }

    /** The screen stays on only while a recording runs, and leaving the camera lets it time out again. */
    @Test
    fun theScreenStaysOnOnlyWhileRecording() {
        var recording by mutableStateOf(false)
        var shown by mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            if (shown) KeepScreenOnWhile(recording)
        }

        compose.runOnIdle { assertFalse(view.keepScreenOn) }
        recording = true
        compose.runOnIdle { assertTrue(view.keepScreenOn) }
        recording = false
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
        recording = true
        compose.runOnIdle { assertTrue(view.keepScreenOn) }
        shown = false
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
    }
}
