package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.audio.AttachmentSpeechFailure
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
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

    @Test fun captureTranscriptFrames() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val shown = mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    if (shown.value) {
                        AudioTranscriptDialog(
                            completed(),
                            onStart = {},
                            onDismiss = { shown.value = false },
                            onGetProvider = {},
                        )
                    }
                }
            }
        }
        repeat(3) { cycle ->
            captureTransitionFrames("transcript/cycle_$cycle/forward") { composeRule.runOnIdle { shown.value = true } }
            captureTransitionFrames(
                "transcript/cycle_$cycle/reverse",
            ) { composeRule.onNodeWithText("Close").performClick() }
            composeRule.runOnIdle { org.junit.Assert.assertFalse(shown.value) }
        }
    }

    private fun captureTransitionFrames(
        name: String,
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
        repeat(32) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }
}
