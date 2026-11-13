package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.AttachmentSpeechFailure
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The received-audio transcript renders inline inside the bubble; no dialog is ever shown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class AudioTranscriptionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    @Test
    fun idleLight() = capture("idle-light", null)

    @Test
    fun processingDark() = capture("working-dark", AudioTranscriptUi(AudioTranscriptPhase.WORKING), Appearance.DARK)

    @Test
    fun completedLight() = capture("complete-light", completed())

    @Test
    fun completedDark() = capture("complete-dark", completed(), Appearance.DARK)

    @Test
    fun completedLargeRtlAmoled() = capture("complete-amoled-rtl-large", completed(), Appearance.AMOLED_RTL)

    @Test
    fun missingProviderLargeRtl() =
        capture(
            "missing-amoled-rtl-large",
            AudioTranscriptUi(AudioTranscriptPhase.FAILED, failure = AttachmentSpeechFailure.PROVIDER_MISSING),
            Appearance.AMOLED_RTL,
        )

    @Test
    fun outdatedProviderLargeRtl() =
        capture(
            "outdated-amoled-rtl-large",
            AudioTranscriptUi(AudioTranscriptPhase.FAILED, failure = AttachmentSpeechFailure.PROVIDER_OUTDATED),
            Appearance.AMOLED_RTL,
        )

    /** A single tap requests the transcript and a second tap hides it, without any dialog. */
    @Test
    fun singleTapTogglesWithoutDialog() {
        val requests = mutableListOf<Boolean>()
        composeRule.setContent {
            WhiteNoiseTheme {
                AudioTranscriptInline(
                    state = if (requests.lastOrNull() == true) completed() else null,
                    enabled = true,
                    onToggle = { requests += it },
                    onRetry = {},
                    onGetProvider = {},
                )
            }
        }
        composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertIsOff().performClick()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf(true), requests)
    }

    private fun capture(
        name: String,
        state: AudioTranscriptUi?,
        appearance: Appearance = Appearance.LIGHT,
    ) {
        val amoledRtl = appearance == Appearance.AMOLED_RTL
        val direction = if (amoledRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = appearance != Appearance.LIGHT,
                amoled = amoledRtl,
                fontScale = if (amoledRtl) 2f else 1f,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    // Bubble-width frame so wrapping matches the received voice bubble.
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.width(280.dp).testTag(FRAME),
                    ) {
                        AudioTranscriptInline(
                            state = state,
                            enabled = true,
                            onToggle = {},
                            onRetry = {},
                            onGetProvider = {},
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
        }
        val toggle = composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertIsDisplayed()
        if (state == null) toggle.assertIsOff() else toggle.assertIsOn()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        composeRule.onNodeWithTag(FRAME).captureRoboImage("src/test/snapshots/audio-transcription-$name.png")
    }

    private fun completed(): AudioTranscriptUi =
        AudioTranscriptUi(
            AudioTranscriptPhase.COMPLETE,
            "The train leaves at ten. I will meet you by the station entrance.",
        )

    private enum class Appearance { LIGHT, DARK, AMOLED_RTL }

    private companion object {
        const val FRAME = "audio.transcription.frame"
    }
}
