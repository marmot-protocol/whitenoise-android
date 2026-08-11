package dev.ipf.whitenoise.android.ui.conversation

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsError
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryDirection
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryEdgeState
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.errorTts
import dev.ipf.whitenoise.android.audio.tts.idleTts
import dev.ipf.whitenoise.android.audio.tts.pausedTts
import dev.ipf.whitenoise.android.audio.tts.speakingTts
import dev.ipf.whitenoise.android.state.TtsRatePreferences
import dev.ipf.whitenoise.android.ui.settings.ttsRateLabel
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TtsTransportBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun label(resId: Int): String = app.getString(resId)

    @Test
    fun fourDistinctNavigationActionsInvokeTheirOwnCallbacks() {
        val clicks = mutableListOf<String>()
        renderBar(
            state = speakingTts(1, 4, 1, 3, "Preview", sentenceIndex = 1, sentenceCount = 2),
            onPreviousSentence = { clicks += "previousSentence" },
            onNextSentence = { clicks += "nextSentence" },
            onPreviousMessage = { clicks += "previousMessage" },
            onNextMessage = { clicks += "nextMessage" },
        )

        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_previous_message)).performClick()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_skip_previous)).performClick()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_skip_next)).performClick()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_next_message)).performClick()

        assertEquals(
            listOf("previousMessage", "previousSentence", "nextSentence", "nextMessage"),
            clicks,
        )
    }

    @Test
    fun progressIdentifiesBothTheSentenceAndTheMessage() {
        renderBar(state = speakingTts(4, 20, 1, 12, "Preview", sentenceIndex = 2, sentenceCount = 8))

        composeRule
            .onNodeWithText(app.getString(R.string.tts_bar_progress, 3, 8, 2, 12))
            .assertIsDisplayed()
    }

    @Test
    fun progressAnimationFramesAreHiddenFromAccessibility() {
        renderBar(
            state =
                speakingTts(
                    4,
                    20,
                    1,
                    12,
                    "Preview",
                    sentenceIndex = 2,
                    sentenceCount = 8,
                    messageProgressFraction = 0.35f,
                ),
        )

        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
        composeRule
            .onNodeWithText(app.getString(R.string.tts_bar_progress, 3, 8, 2, 12))
            .assertIsDisplayed()
    }

    @Test
    fun pausedStateOffersPlayWithoutLosingNavigation() {
        var resumed = false
        renderBar(
            state = pausedTts(1, 4, 0, 3, "Preview", sentenceIndex = 1, sentenceCount = 2),
            onResume = { resumed = true },
        )

        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_previous_message)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_skip_previous)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_skip_next)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_next_message)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_play)).performClick()
        assertEquals(true, resumed)
    }

    @Test
    fun errorStateDisablesNavigationButKeepsStop() {
        var stopped = false
        renderBar(
            state = errorTts(TtsError.Synthesis, 2, 4, 1, 3, "Preview"),
            onStop = { stopped = true },
        )

        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_previous_message)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_skip_previous)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_skip_next)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_next_message)).assertIsNotEnabled()
        // Error clears the queue, so no play or pause control may exist —
        // a permanently disabled resume slot would lie to accessibility focus.
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_play)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_pause)).assertDoesNotExist()
        composeRule.onNodeWithText(label(R.string.tts_bar_error)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_stop)).performClick()
        assertEquals(true, stopped)
    }

    @Test
    fun rateButtonOpensPickerWithoutCyclingAndEveryPresetCanBeChosen() {
        val selections = mutableListOf<Float?>()
        renderBar(
            state = speakingTts(1, 4, 1, 3, "Preview", sentenceIndex = 1, sentenceCount = 2),
            onRateSelected = { selections += it },
        )
        val control = composeRule.onNodeWithContentDescription(rateControlDescription())

        control.performClick()
        composeRule.onNodeWithText(label(R.string.tts_settings_rate_system)).assertIsDisplayed()
        assertTrue(selections.isEmpty())

        composeRule
            .onNode(hasText(label(R.string.tts_settings_rate_system)) and isSelectable())
            .performClick()
        TtsRatePreferences.PRESET_RATES.forEach { rate ->
            control.performClick()
            composeRule.onNode(hasText(ttsRateLabel(rate, Locale.US)) and isSelectable()).performClick()
        }

        assertEquals(listOf<Float?>(null) + TtsRatePreferences.PRESET_RATES, selections)
    }

    @Test
    fun customRateEditorAppliesBoundariesAndOneDecimalNormalization() {
        val selections = mutableListOf<Float?>()
        renderBar(
            state = speakingTts(1, 4, 1, 3, "Preview", sentenceIndex = 1, sentenceCount = 2),
            onRateSelected = { selections += it },
        )

        listOf("0.1" to 0.1f, "10.0" to 10.0f, "1.26" to 1.3f).forEach { (input, expected) ->
            openCustomRateEditor()
            composeRule.onNode(hasSetTextAction()).performTextReplacement(input)
            composeRule.onNodeWithText(label(R.string.tts_rate_apply)).performClick()
            assertEquals(expected, selections.last())
        }
    }

    @Test
    fun customRateEditorBlocksInvalidInputWithAnInlineError() {
        val selections = mutableListOf<Float?>()
        renderBar(
            state = speakingTts(1, 4, 1, 3, "Preview", sentenceIndex = 1, sentenceCount = 2),
            onRateSelected = { selections += it },
        )

        openCustomRateEditor()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("10.01")

        composeRule.onNodeWithText(label(R.string.tts_rate_custom_error)).assertIsDisplayed()
        composeRule.onNodeWithText(label(R.string.tts_rate_apply)).assertIsNotEnabled()
        assertTrue(selections.isEmpty())
    }

    @Test
    fun appliedCustomRateUpdatesTheVisibleControlImmediately() {
        composeRule.setContent {
            var rateOverride by remember { mutableStateOf<Float?>(1.0f) }
            WhiteNoiseTheme(darkTheme = false) {
                TtsTransportBarContent(
                    state = speakingTts(1, 4, 1, 3, "Preview", sentenceIndex = 1, sentenceCount = 2),
                    rateOverride = rateOverride,
                    activeRate = rateOverride ?: 1.0f,
                    onPause = {},
                    onResume = {},
                    onPreviousSentence = {},
                    onNextSentence = {},
                    onPreviousMessage = {},
                    onNextMessage = {},
                    onRateSelected = { rateOverride = it },
                    onStop = {},
                    modifier = Modifier.width(360.dp),
                )
            }
        }

        openCustomRateEditor()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("1.25")
        composeRule.onNodeWithText(label(R.string.tts_rate_apply)).performClick()

        val description = app.getString(R.string.tts_bar_rate_control, ttsRateLabel(1.3f, Locale.US))
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    @Test
    fun narrowWidthAndLargeFontKeepEveryActionVisible() {
        renderBar(
            state = speakingTts(4, 20, 1, 12, "A long preview", sentenceIndex = 2, sentenceCount = 8),
            barWidth = 320,
            fontScale = 2f,
        )

        val barBounds = composeRule.onNodeWithTag(BAR_TAG).getUnclippedBoundsInRoot()
        listOf(
            R.string.tts_bar_previous_message,
            R.string.tts_bar_skip_previous,
            R.string.tts_bar_pause,
            R.string.tts_bar_skip_next,
            R.string.tts_bar_next_message,
            R.string.tts_bar_stop,
        ).forEach { resId ->
            val action = composeRule.onNodeWithContentDescription(label(resId))
            action.assertIsDisplayed()
            // Unclipped bounds inside the bar prove the action is fully
            // visible — assertIsDisplayed alone passes on partial clipping.
            val bounds = action.getUnclippedBoundsInRoot()
            assertTrue(
                "${label(resId)} must sit fully inside the bar, was $bounds within $barBounds",
                bounds.left >= barBounds.left &&
                    bounds.right <= barBounds.right &&
                    bounds.top >= barBounds.top &&
                    bounds.bottom <= barBounds.bottom,
            )
        }
    }

    @Test
    fun pendingEdgeLoadDisablesNavigationAndAnnouncesTheLoadingState() {
        renderBar(
            state = speakingTts(1, 4, 0, 3, "Preview", sentenceIndex = 1, sentenceCount = 2),
            historyEdge = TtsHistoryEdgeState.Loading(TtsHistoryDirection.Older),
        )

        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_previous_message)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_skip_previous)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_skip_next)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_next_message)).assertIsNotEnabled()
        composeRule.onNodeWithText(label(R.string.tts_bar_history_loading)).assertIsDisplayed()
        // The status text must be a polite live region, or TalkBack never
        // narrates the state change to a listener who cannot see the bar.
        composeRule
            .onNodeWithText(label(R.string.tts_bar_history_loading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        // Playback control stays live: only navigation waits for the page.
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_pause)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_stop)).assertIsEnabled()
    }

    @Test
    fun oneChunkNoRangeCompletionAnimatesProgressBeforeDismissal() {
        composeRule.mainClock.autoAdvance = false
        var state: TtsState by mutableStateOf(singleSentenceSpeakingState())
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                val displayState = rememberTtsTransportDisplayState(state) ?: return@WhiteNoiseTheme
                TtsTransportBarContent(
                    state = displayState,
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
                    modifier = Modifier.width(360.dp).testTag(BAR_TAG),
                )
            }
        }

        composeRule.onNodeWithTag(BAR_TAG).assertIsDisplayed()

        composeRule.runOnIdle {
            state = singleSentenceTerminalState()
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_pause)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_play)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_previous_message)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_next_message)).assertIsNotEnabled()

        composeRule.mainClock.advanceTimeBy(201)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BAR_TAG).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(50)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BAR_TAG).assertDoesNotExist()
    }

    private fun singleSentenceSpeakingState() =
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

    private fun singleSentenceTerminalState() =
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

    @Test
    fun failedEdgeLoadKeepsNavigationEnabledForRetryAndShowsTheError() {
        var previousTaps = 0
        renderBar(
            state = pausedTts(1, 4, 0, 3, "Preview", sentenceIndex = 1, sentenceCount = 2),
            onPreviousMessage = { previousTaps += 1 },
            historyEdge = TtsHistoryEdgeState.Failed(TtsHistoryDirection.Older),
        )

        composeRule.onNodeWithText(label(R.string.tts_bar_history_error)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_previous_message)).assertIsEnabled()
        composeRule.onNodeWithContentDescription(label(R.string.tts_bar_previous_message)).performClick()
        assertEquals(1, previousTaps)
    }

    @Suppress("LongParameterList")
    private fun renderBar(
        state: TtsState,
        barWidth: Int = 360,
        fontScale: Float = 1f,
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
        onPreviousSentence: () -> Unit = {},
        onNextSentence: () -> Unit = {},
        onPreviousMessage: () -> Unit = {},
        onNextMessage: () -> Unit = {},
        onRateSelected: (Float?) -> Unit = {},
        onStop: () -> Unit = {},
        historyEdge: TtsHistoryEdgeState? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                WithFontScale(fontScale) {
                    TtsTransportBarContent(
                        state = state,
                        rateOverride = 1.0f,
                        activeRate = 1.0f,
                        onPause = onPause,
                        onResume = onResume,
                        onPreviousSentence = onPreviousSentence,
                        onNextSentence = onNextSentence,
                        onPreviousMessage = onPreviousMessage,
                        onNextMessage = onNextMessage,
                        onRateSelected = onRateSelected,
                        onStop = onStop,
                        modifier = Modifier.width(barWidth.dp).testTag(BAR_TAG),
                        historyEdge = historyEdge,
                    )
                }
            }
        }
    }

    private fun rateControlDescription(): String {
        val rateLabel = ttsRateLabel(1.0f, Locale.US)
        return app.getString(R.string.tts_bar_rate_control, rateLabel)
    }

    private fun openCustomRateEditor() {
        composeRule.onNodeWithContentDescription(rateControlDescription()).performClick()
        composeRule.onNode(hasText(label(R.string.tts_rate_custom)) and isSelectable()).performClick()
    }

    @Suppress("FunctionNaming")
    @Composable
    private fun WithFontScale(
        fontScale: Float,
        content: @Composable () -> Unit,
    ) {
        val current = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density = current.density, fontScale = fontScale),
            content = content,
        )
    }

    private companion object {
        const val BAR_TAG = "tts-transport-bar"
    }
}
