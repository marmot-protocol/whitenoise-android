package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.whitenoise.android.state.POLL_FIVE_MINUTES_SECONDS
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PollScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** The open card shows native vote counts, participants and the account's selection. */
    @Test fun openPollLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(Modifier.width(360.dp).testTag("poll-card")) {
                    PollCard(poll(), canVote = true, onVote = {})
                }
            }
        }
        composeRule.onNodeWithTag("poll-card").captureRoboImage("src/test/snapshots/poll_card_open_light.png")
    }

    /** An RTL closed poll keeps its tally readable without active voting controls. */
    @Test fun closedPollDarkRtl() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.width(360.dp).testTag("poll-card")) {
                        PollCard(poll().copy(open = false), canVote = false, onVote = {})
                    }
                }
            }
        }
        composeRule.onNodeWithTag("poll-card").captureRoboImage("src/test/snapshots/poll_card_closed_dark_rtl.png")
    }

    /** The group composer offers question, options and choice type before publishing. */
    @Test fun createFormLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(Modifier.width(320.dp).testTag("poll-create-form")) {
                    PollCreateForm(
                        question = "Where should we meet?",
                        options = listOf("Coffee shop", "Library"),
                        multiple = false,
                        deadlineDurationSeconds = POLL_FIVE_MINUTES_SECONDS,
                        enabled = true,
                        onQuestionChange = {},
                        onOptionChange = { _, _ -> },
                        onRemoveOption = {},
                        onAddOption = {},
                        onMultipleChange = {},
                        onDeadlineChange = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag("poll-create-form").captureRoboImage("src/test/snapshots/poll_create_form_light.png")
    }

    /** Large text and RTL keep both choice controls reachable on a narrow window. */
    @Test fun createFormLargeRtl() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalDensity provides Density(1f, 2f),
                ) {
                    Surface(Modifier.width(320.dp).testTag("poll-create-form")) {
                        PollCreateForm(
                            question = "Where should we meet?",
                            options = listOf("Coffee shop", "Library"),
                            multiple = true,
                            deadlineDurationSeconds = null,
                            enabled = true,
                            onQuestionChange = {},
                            onOptionChange = { _, _ -> },
                            onRemoveOption = {},
                            onAddOption = {},
                            onMultipleChange = {},
                            onDeadlineChange = {},
                        )
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag("poll-create-form")
            .captureRoboImage("src/test/snapshots/poll_create_form_large_rtl.png")
        composeRule.onNodeWithText("30 days").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag("poll-create-form")
            .captureRoboImage("src/test/snapshots/poll_create_form_large_rtl_choices.png")
    }

    /** Shared native projection fixture with two options and a selected vote. */
    private fun poll() =
        PollProjectionFfi(
            question = "Where should we meet?",
            options =
                listOf(
                    PollOptionResultFfi("a", "Coffee shop", 3uL),
                    PollOptionResultFfi("b", "Library", 1uL),
                ),
            pollType = PollTypeFfi.SINGLE_CHOICE,
            participants = 4uL,
            localSelection = listOf("a"),
            creator = "creator",
            endsAt = null,
            open = true,
        )
}
