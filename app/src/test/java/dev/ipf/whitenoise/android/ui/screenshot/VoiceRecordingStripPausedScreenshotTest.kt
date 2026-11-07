package dev.ipf.whitenoise.android.ui.screenshot

import android.media.AudioManager
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.AudioFocusOwner
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingResult
import dev.ipf.whitenoise.android.audio.VoiceRecordingSession
import dev.ipf.whitenoise.android.ui.conversation.composer.RecordingStripLeading
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel baseline for the voice-recording strip while a take is paused by an in-app audio handoff. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class VoiceRecordingStripPausedScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Captures a take paused by an in-app handoff: steady dot, kept timer and the resume control. */
    @Test
    fun voiceRecordingStripPausedLight() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val voiceRecording =
            VoiceRecordingController(
                context = context,
                outputDirectory = context.cacheDir,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                onPermissionRequest = { true },
                onRecordingComplete = { _, _ -> },
                onError = {},
                recorderFactory = { _, file, _ -> PausableRecordingSession(file) },
                mainDispatcher = Dispatchers.Unconfined,
                recorderDispatcher = Dispatchers.Unconfined,
                elapsedRealtime = { 0L },
            )
        try {
            assertTrue(voiceRecording.start())
            assertTrue(
                AudioFocusOwner.acquire(
                    owner = AudioFocusOwner.Owner.Tts,
                    audioAttributes = AudioFocusOwner.ttsSpeechAttributes,
                    focusGain = AudioManager.AUDIOFOCUS_GAIN,
                    onFocusLoss = {},
                    onOwnerSurrender = {},
                ),
            )
            assertTrue(voiceRecording.isPaused)
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface(modifier = Modifier.width(320.dp).testTag(RECORDING_STRIP_TAG)) {
                        RecordingStripLeading(controller = voiceRecording)
                    }
                }
            }
            composeRule.mainClock.advanceTimeBy(350L)

            composeRule.onNodeWithTag("composer.voice.resume").assertIsDisplayed()
            composeRule
                .onNodeWithTag(RECORDING_STRIP_TAG)
                .captureRoboImage("src/test/snapshots/composer_voice_recording_strip_paused_light.png")
        } finally {
            AudioFocusOwner.release(AudioFocusOwner.Owner.Tts)
            voiceRecording.release()
        }
    }

    /** Stands in for the platform encoder so the strip can be captured mid-take. */
    private class PausableRecordingSession(
        private val file: java.io.File,
    ) : VoiceRecordingSession {
        override fun start() = file.writeText("captured audio")

        override fun stop(): VoiceRecordingResult? = VoiceRecordingResult(file, 0L)

        override fun cancel() {
            file.delete()
        }
    }

    private companion object {
        const val RECORDING_STRIP_TAG = "composer-voice-recording-strip"
    }
}
