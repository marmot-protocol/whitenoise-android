@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.ipf.whitenoise.android.translation.MessageSideDiscSpacing
import dev.ipf.whitenoise.android.translation.MessageTranslationToggleSlotWidth
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleBottomAlignmentLine
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageSenderAvatarSlot
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
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

/**
 * A received voice message keeps its transcribe disc in the side gutter, like translation, and shows the
 * transcript as plain text directly beneath the voice row; no dialog is ever shown.
 */
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

    /** One tap on the side disc requests the transcript and a second tap hides it, without any dialog. */
    @Test
    fun singleTapTogglesWithoutDialog() {
        val requests = mutableListOf<Boolean>()
        composeRule.setContent {
            WhiteNoiseTheme {
                var on by remember { mutableStateOf(false) }
                AudioTranscribeDisc(
                    on = on,
                    working = false,
                    enabled = true,
                    onToggle = {
                        requests += it
                        on = it
                    },
                )
            }
        }
        val disc = composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG)
        disc.assertIsOff().performClick()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        disc.assertIsOn().performClick()
        disc.assertIsOff()
        assertEquals(listOf(true, false), requests)
    }

    /** The disc stays hidden until a received recording registers, so other bubbles keep an empty gutter. */
    @Test
    fun toggleEmitsNothingWithoutRecording() {
        val toggle = AudioTranscriptionToggleState()
        composeRule.setContent { WhiteNoiseTheme { AudioTranscriptionToggle(toggle) } }
        composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertDoesNotExist()
        composeRule.runOnIdle { toggle.register(Any(), ready = true, working = false) }
        composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertIsDisplayed().assertIsOff()
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
                    Surface(modifier = Modifier.width(360.dp).testTag(FRAME)) {
                        IncomingVoiceMessage(state)
                    }
                }
            }
        }
        val disc = composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertIsDisplayed()
        if (state == null) disc.assertIsOff() else disc.assertIsOn()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        composeRule.onNodeWithTag(FRAME).captureRoboImage("src/test/snapshots/audio-transcription-$name.png")
    }

    /** The production order: avatar, bubble with the voice row and transcript, then the side disc column. */
    @Composable
    private fun IncomingVoiceMessage(state: AudioTranscriptUi?) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MessageSenderAvatarSlot(
                showSenderAvatar = true,
                title = "Alex",
                seed = "alex",
                pictureUrl = null,
                enabled = true,
                alignToBubbleBottom = true,
                onClick = {},
            )
            Column(modifier = Modifier.weight(1f, fill = false).alignBy(MessageBubbleBottomAlignmentLine)) {
                MessageBubbleFrame(
                    presentation = messageBubblePresentation(deleted = false, mine = false),
                    highlighted = false,
                    mine = false,
                    mentionedSelf = false,
                    mentionedYouLabel = "Mentioned you",
                ) {
                    VoiceAttachmentContent(
                        loading = false,
                        failed = false,
                        startDownload = false,
                        localFileAvailable = true,
                        isPlaying = false,
                        isPaused = false,
                        activePositionMs = 0,
                        activeDurationMs = 0,
                        totalDurationMs = 14_000,
                        progressFraction = 0f,
                        outgoing = false,
                        onLongPress = {},
                        onActionClick = {},
                    )
                    AudioTranscriptBody(state = state, onRetry = {}, onGetProvider = {})
                }
            }
            Column(
                modifier = Modifier.alignBy { it.measuredHeight },
                verticalArrangement = Arrangement.spacedBy(MessageSideDiscSpacing),
            ) {
                AudioTranscribeDisc(
                    on = state != null,
                    working = state?.phase == AudioTranscriptPhase.WORKING,
                    enabled = true,
                    onToggle = {},
                    modifier = Modifier.width(MessageTranslationToggleSlotWidth),
                )
            }
        }
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
