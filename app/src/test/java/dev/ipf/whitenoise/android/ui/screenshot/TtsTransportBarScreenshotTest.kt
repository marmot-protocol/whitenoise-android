package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.tts.TtsError
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryDirection
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryEdgeState
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.errorTts
import dev.ipf.whitenoise.android.audio.tts.idleTts
import dev.ipf.whitenoise.android.audio.tts.pausedTts
import dev.ipf.whitenoise.android.audio.tts.speakingTts
import dev.ipf.whitenoise.android.ui.conversation.TtsTransportBarContent
import dev.ipf.whitenoise.android.ui.conversation.ttsTerminalCompletionDisplayState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baselines for the read-aloud transport's separated sentence and
 * message controls across playback states and themes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TtsTransportBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ttsTransportBarSpeakingLight() {
        render(speaking(), darkTheme = false, amoled = false)
        capture("tts_transport_bar_speaking_light")
    }

    @Test
    fun ttsTransportBarSpeakingDark() {
        render(speaking(), darkTheme = true, amoled = false)
        capture("tts_transport_bar_speaking_dark")
    }

    @Test
    fun ttsTransportBarSpeakingAmoled() {
        render(speaking(), darkTheme = true, amoled = true)
        capture("tts_transport_bar_speaking_amoled")
    }

    @Test
    fun ttsTransportBarPausedLight() {
        render(paused(), darkTheme = false, amoled = false)
        capture("tts_transport_bar_paused_light")
    }

    @Test
    fun ttsTransportBarPausedDark() {
        render(paused(), darkTheme = true, amoled = false)
        capture("tts_transport_bar_paused_dark")
    }

    @Test
    fun ttsTransportBarPausedAmoled() {
        render(paused(), darkTheme = true, amoled = true)
        capture("tts_transport_bar_paused_amoled")
    }

    @Test
    fun ttsTransportBarErrorLight() {
        render(error(), darkTheme = false, amoled = false)
        capture("tts_transport_bar_error_light")
    }

    @Test
    fun ttsTransportBarErrorDark() {
        render(error(), darkTheme = true, amoled = false)
        capture("tts_transport_bar_error_dark")
    }

    @Test
    fun ttsTransportBarErrorAmoled() {
        render(error(), darkTheme = true, amoled = true)
        capture("tts_transport_bar_error_amoled")
    }

    @Test
    fun ttsTransportBarHistoryLoadingLight() {
        render(speaking(), darkTheme = false, amoled = false, historyEdge = loadingEdge())
        capture("tts_transport_bar_history_loading_light")
    }

    @Test
    fun ttsTransportBarHistoryLoadingDark() {
        render(speaking(), darkTheme = true, amoled = false, historyEdge = loadingEdge())
        capture("tts_transport_bar_history_loading_dark")
    }

    @Test
    fun ttsTransportBarHistoryLoadingAmoled() {
        render(speaking(), darkTheme = true, amoled = true, historyEdge = loadingEdge())
        capture("tts_transport_bar_history_loading_amoled")
    }

    @Test
    fun ttsTransportBarHistoryErrorLight() {
        render(paused(), darkTheme = false, amoled = false, historyEdge = failedEdge())
        capture("tts_transport_bar_history_error_light")
    }

    @Test
    fun ttsTransportBarHistoryErrorDark() {
        render(paused(), darkTheme = true, amoled = false, historyEdge = failedEdge())
        capture("tts_transport_bar_history_error_dark")
    }

    @Test
    fun ttsTransportBarHistoryErrorAmoled() {
        render(paused(), darkTheme = true, amoled = true, historyEdge = failedEdge())
        capture("tts_transport_bar_history_error_amoled")
    }

    @Test
    fun ttsTransportBarTerminalCompletionLight() {
        render(terminalCompletion(), darkTheme = false, amoled = false)
        capture("tts_transport_bar_terminal_completion_light")
    }

    @Test
    fun ttsTransportBarTerminalCompletionDark() {
        render(terminalCompletion(), darkTheme = true, amoled = false)
        capture("tts_transport_bar_terminal_completion_dark")
    }

    @Test
    fun ttsTransportBarTerminalCompletionAmoled() {
        render(terminalCompletion(), darkTheme = true, amoled = true)
        capture("tts_transport_bar_terminal_completion_amoled")
    }

    private val preview = "Alice: The quick brown fox jumps over it"

    private fun speaking(): TtsState =
        speakingTts(
            chunkIndex = 4,
            chunkCount = 20,
            messageIndex = 1,
            messageCount = 12,
            messagePreview = preview,
            sentenceIndex = 2,
            sentenceCount = 8,
            messageProgressFraction = 0.35f,
        )

    private fun paused(): TtsState =
        pausedTts(
            chunkIndex = 4,
            chunkCount = 20,
            messageIndex = 1,
            messageCount = 12,
            messagePreview = preview,
            sentenceIndex = 2,
            sentenceCount = 8,
            messageProgressFraction = 0.35f,
        )

    private fun error(): TtsState =
        errorTts(
            error = TtsError.Synthesis,
            chunkIndex = 4,
            chunkCount = 20,
            messageIndex = 1,
            messageCount = 12,
            messagePreview = preview,
            sentenceIndex = 2,
            sentenceCount = 8,
        )

    private fun terminalCompletion(): TtsState {
        val lastActive =
            speakingTts(
                chunkIndex = 0,
                chunkCount = 1,
                messageIndex = 0,
                messageCount = 1,
                messagePreview = "Hello.",
                sentenceIndex = 0,
                sentenceCount = 1,
                messageProgressFraction = 0f,
                messageProgressGeneration = 1L,
            )
        val terminalIdle =
            idleTts(
                chunkIndex = 1,
                chunkCount = 1,
                messageIndex = 1,
                messageCount = 1,
                messagePreview = "Hello.",
                sentenceIndex = 1,
                sentenceCount = 1,
                messageProgressFraction = 1f,
                messageProgressGeneration = 1L,
            )
        return ttsTerminalCompletionDisplayState(lastActive, terminalIdle)
    }

    private fun loadingEdge(): TtsHistoryEdgeState = TtsHistoryEdgeState.Loading(TtsHistoryDirection.Older)

    private fun failedEdge(): TtsHistoryEdgeState = TtsHistoryEdgeState.Failed(TtsHistoryDirection.Older)

    private fun render(
        state: TtsState,
        darkTheme: Boolean,
        amoled: Boolean,
        historyEdge: TtsHistoryEdgeState? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                TtsTransportBarContent(
                    state = state,
                    rateOverride = 1.0f,
                    activeRate = 1.0f,
                    onPause = {},
                    onResume = {},
                    onPreviousSentence = {},
                    onNextSentence = {},
                    onPreviousMessage = {},
                    onNextMessage = {},
                    onRateSelected = {},
                    onStop = {},
                    modifier = Modifier.width(360.dp).testTag(TAG),
                    historyEdge = historyEdge,
                )
            }
        }
    }

    private fun capture(name: String) {
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private companion object {
        const val TAG = "tts-transport-bar"
    }
}
