package dev.ipf.whitenoise.android.ui.conversation.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Caption remains readable before fetch and every contact action is directly discoverable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactMessageBubbleTest {
    @get:Rule val composeRule = createComposeRule()

    /** Actions route independently; an in-progress download disables every action. */
    @Test fun captionAndActionsRemainReachable() {
        var views = 0
        var adds = 0
        var saves = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactMessageBubble(
                    contact = SharedContact("Ada Example", "+1234567", "ada@example.org"),
                    busy = false,
                    error = null,
                    onView = { views++ },
                    onAdd = { adds++ },
                    onSave = { saves++ },
                )
            }
        }
        composeRule.onNodeWithText("Ada Example").assertIsDisplayed()
        composeRule.onNodeWithText("View contact").performClick()
        composeRule.onNodeWithText("Add to contacts").performClick()
        composeRule.onNodeWithText("Save VCF").performClick()
        assertEquals(1, views)
        assertEquals(1, adds)
        assertEquals(1, saves)
    }

    /** Failed fetch retains caption and exposes a retryable action after progress ends. */
    @Test fun progressAndFailureKeepCaptionVisible() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactMessageBubble(
                    contact = SharedContact("Ada Example", "+1234567", null),
                    busy = true,
                    error = "Could not load",
                    onView = {},
                    onAdd = {},
                    onSave = {},
                )
            }
        }
        composeRule.onNodeWithText("Ada Example").assertIsDisplayed()
        composeRule.onNodeWithText("Could not load").assertIsDisplayed()
        composeRule.onNodeWithText("View contact").assertIsNotEnabled()
        composeRule.onNodeWithText("Save VCF").assertIsNotEnabled()
    }
}
