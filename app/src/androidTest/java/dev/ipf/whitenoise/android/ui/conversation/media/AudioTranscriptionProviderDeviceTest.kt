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
import dev.ipf.whitenoise.android.audio.OFFLINE_SPEECH_TO_TEXT_PACKAGE
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires an installed pre-0.21.0 OSTT provider; no model or readable recording is needed. */
@RunWith(AndroidJUnit4::class)
@ManualDeviceFixture
class AudioTranscriptionProviderDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun refusesUnqualifiedProvider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = context.packageManager.getPackageInfo(OFFLINE_SPEECH_TO_TEXT_PACKAGE, 0)
        assertTrue("This fixture requires OSTT older than 0.21.0", provider.longVersionCode < 58L)
        val absent = File(context.cacheDir, "absent-audio-${System.nanoTime()}.ogg")
        composeRule.setContent {
            WhiteNoiseTheme { ReceivedAudioTranscription(absent) }
        }
        composeRule.onNodeWithTag("audio.transcribe").performClick()
        composeRule.onNode(hasText("Transcribe") and hasAnyAncestor(isDialog())).performClick()
        val explanation = context.getString(R.string.audio_transcription_provider_outdated)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(explanation).fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText(explanation).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.dictation_ostt_recommendation_title)).assertIsDisplayed()
    }
}
