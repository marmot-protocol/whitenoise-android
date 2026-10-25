package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.AttachmentSpeechFailure
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class AudioTranscriptionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    @Test
    fun confirmationLight() = capture("confirm-light", AudioTranscriptUi(AudioTranscriptPhase.CONFIRM))

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

    private fun capture(
        name: String,
        state: AudioTranscriptUi,
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
                    AudioTranscriptDialog(state, onStart = {}, onDismiss = {}, onGetProvider = {})
                }
            }
        }
        composeRule.onNodeWithText("Audio transcript").assertIsDisplayed()
        val dismissLabel =
            when (state.phase) {
                AudioTranscriptPhase.CONFIRM, AudioTranscriptPhase.WORKING -> "Cancel"
                else -> "Close"
            }
        composeRule.onNodeWithText(dismissLabel).assertIsDisplayed()
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/audio-transcription-$name.png")
    }

    private fun completed(): AudioTranscriptUi =
        AudioTranscriptUi(
            AudioTranscriptPhase.COMPLETE,
            "The train leaves at ten. I will meet you by the station entrance.",
        )

    private enum class Appearance { LIGHT, DARK, AMOLED_RTL }
}
