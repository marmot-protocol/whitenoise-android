package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerAccess
import dev.ipf.whitenoise.android.audio.ConversationDictationComposerPhase
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationDictationRecoveryScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun remainingAudioPanelLight() {
        render(ConversationDictationComposerPhase.RemainingAudio)
        capture("dictation_remaining_audio_light")
    }

    @Test
    fun remainingAudioPanelDark() {
        render(ConversationDictationComposerPhase.RemainingAudio, theme = "dark")
        capture("dictation_remaining_audio_dark")
    }

    @Test
    fun remainingAudioPanelLargeRtlAmoledKeepsDecisionsReachable() {
        render(ConversationDictationComposerPhase.RemainingAudio, theme = "amoled", rtl = true)
        composeRule.onNodeWithText("Keep for later").assertIsDisplayed()
        composeRule.onNodeWithText("Retry remaining audio").assertIsDisplayed()
        capture("dictation_remaining_audio_large_rtl_amoled")
    }

    @Test
    fun optionalRetryProgressCanBeInspectedWithoutStartingAnotherCapture() {
        render(ConversationDictationComposerPhase.Transcribing, theme = "dark")
        composeRule
            .onNode(hasText("Transcribing…") and hasAnyAncestor(hasTestTag("dictation-recovery-panel")))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Retry remaining audio").assertDoesNotExist()
        capture("dictation_remaining_audio_progress_dark")
    }

    @Test
    fun unreadableAudioExposesExplanation() {
        render(ConversationDictationComposerPhase.AudioStateUnavailable, rtl = true)
        composeRule.onNodeWithTag("dictation-recovery-panel").assertIsDisplayed()
        composeRule.onNodeWithText("Retry remaining audio").assertDoesNotExist()
        capture("dictation_remaining_audio_unavailable_large_rtl")
    }

    @Test
    fun pendingNativeClosureIsInspectableWithoutRetryOrDiscard() {
        render(ConversationDictationComposerPhase.ClosingMicrophone, theme = "dark")
        composeRule.onNodeWithText("Retry remaining audio").assertDoesNotExist()
        composeRule.onNodeWithText("Discard").assertDoesNotExist()
        capture("dictation_remaining_audio_closing_dark")
    }

    @Test
    fun discardRequiresASeparateConfirmation() {
        var discarded = 0
        render(ConversationDictationComposerPhase.RemainingAudio, onDiscard = { discarded++ })
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithTag("dictation-discard-confirmation").assertIsDisplayed()
        assertEquals(0, discarded)
        capture("dictation_remaining_audio_discard_confirmation")
    }

    private fun render(
        phase: ConversationDictationComposerPhase,
        theme: String = "light",
        rtl: Boolean = false,
        onDiscard: () -> Unit = {},
    ) {
        val access = ConversationDictationComposerAccess(1L, 1L, phase, ConversationDictationFailure.NoMatch)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(
                    darkTheme = theme != "light",
                    amoled = theme == "amoled",
                    fontScale = if (rtl) 2f else 1f,
                ) {
                    Surface(Modifier.width(360.dp)) {
                        Column(Modifier.testTag("recovery-status-frame")) {
                            ConversationDictationRecoveryStatus(access)
                            ConversationDictationRecoveryPanel(access, true, {}, onDiscard, {})
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/$name.png")
    }
}
