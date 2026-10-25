package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires the real Offline Speech to Text service and an installed English speech model. */
@RunWith(AndroidJUnit4::class)
@ManualDeviceFixture
class AudioTranscriptionDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun transcribesAcrossLongPause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("received-speech-", ".ogg", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("transcription/long-pause.ogg").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            composeRule.setContent {
                WhiteNoiseTheme { ReceivedAudioTranscription(file) }
            }
            composeRule.onNodeWithTag("audio.transcribe").performClick()
            // Consent is explicit and cancellable before the provider receives audio.
            composeRule.onNodeWithText("Cancel").performClick()
            composeRule.onNodeWithText("Audio transcript").assertDoesNotExist()
            composeRule.onNodeWithTag("audio.transcribe").performClick()
            composeRule.onNode(hasText("Transcribe") and hasAnyAncestor(isDialog())).performClick()
            val working = instrumentation.targetContext.getString(R.string.audio_transcription_working)
            composeRule.waitUntil(timeoutMillis = 300_000) {
                composeRule
                    .onAllNodesWithText(working)
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            composeRule.onNodeWithText("hello world", substring = true, ignoreCase = true).assertIsDisplayed()
            composeRule.onNodeWithText("good morning", substring = true, ignoreCase = true).assertIsDisplayed()
            composeRule.onNodeWithText("Close").performClick()
            composeRule.onNodeWithText("hello world", substring = true, ignoreCase = true).assertDoesNotExist()
        } finally {
            file.delete()
        }
    }
}
