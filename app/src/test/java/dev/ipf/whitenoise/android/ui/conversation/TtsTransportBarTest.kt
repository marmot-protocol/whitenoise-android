package dev.ipf.whitenoise.android.ui.conversation

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsError
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.errorTts
import dev.ipf.whitenoise.android.audio.tts.pausedTts
import dev.ipf.whitenoise.android.audio.tts.speakingTts
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
        onStop: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                WithFontScale(fontScale) {
                    TtsTransportBarContent(
                        state = state,
                        rateLabel = "1×",
                        onPause = onPause,
                        onResume = onResume,
                        onPreviousSentence = onPreviousSentence,
                        onNextSentence = onNextSentence,
                        onPreviousMessage = onPreviousMessage,
                        onNextMessage = onNextMessage,
                        onCycleRate = {},
                        onStop = onStop,
                        modifier = Modifier.width(barWidth.dp).testTag(BAR_TAG),
                    )
                }
            }
        }
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
