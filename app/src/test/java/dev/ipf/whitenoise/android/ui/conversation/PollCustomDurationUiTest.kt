package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the custom deadline editor through the rendered poll form. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PollCustomDurationUiTest {
    @get:Rule val composeRule = createComposeRule()

    /** Switching and canceling keep the custom draft while errors follow its value. */
    @Test fun validatesAndPreservesCustomInput() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                var selection by remember { mutableStateOf(PollDeadlineSelection()) }
                Surface(Modifier.width(320.dp)) {
                    PollCreateForm(
                        question = "Where?",
                        options = listOf("Home", "Cafe"),
                        multiple = false,
                        deadlineSelection = selection,
                        enabled = true,
                        deadlineIssue =
                            if (selection.customSelected && selection.customValue.isNotBlank()) {
                                validatePollDeadlineSelection(selection).issue
                            } else {
                                null
                            },
                        onQuestionChange = {},
                        onOptionChange = { _, _ -> },
                        onRemoveOption = {},
                        onAddOption = {},
                        onMultipleChange = {},
                        onDeadlineChange = { selection = it },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Custom time").performScrollTo().performClick()
        composeRule.onNodeWithTag("poll-custom-duration").performTextInput("31")
        composeRule.onNodeWithText("Days").performScrollTo().performClick()
        composeRule
            .onNodeWithText("Enter a whole number from 1 second to 30 days.")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("poll-custom-duration").performTextReplacement("25")
        composeRule.onNodeWithText("Hours").performScrollTo().performClick()
        composeRule.onNodeWithText("5 minutes").performScrollTo().performClick()
        composeRule.onNodeWithText("Custom time").performScrollTo().performClick()
        composeRule.onNodeWithTag("poll-custom-duration").assertTextContains("25")
        composeRule.onNodeWithText("Use previous deadline").performScrollTo().performClick()
        composeRule.onNodeWithText("Custom time").performScrollTo().performClick()
        composeRule.onNodeWithTag("poll-custom-duration").assertTextContains("25")
        composeRule.onNodeWithText("Poll closes 1 day, 1 hour after posting.").performScrollTo().assertIsDisplayed()
    }
}
