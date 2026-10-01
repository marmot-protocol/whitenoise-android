package dev.ipf.whitenoise.android.ui.conversation.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val actionLabels = listOf("View contact", "Add to contacts", "Save VCF")
        val actionCenters =
            actionLabels.map { label ->
                composeRule
                    .onNodeWithContentDescription(label)
                    .fetchSemanticsNode()
                    .boundsInRoot.center.y
            }
        assertTrue("all three contact actions share one row", actionCenters.max() - actionCenters.min() < 1f)
        composeRule.onNodeWithText("View").assertIsDisplayed()
        composeRule.onNodeWithText("Add").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("View contact").performClick()
        composeRule.onNodeWithContentDescription("Add to contacts").performClick()
        composeRule.onNodeWithContentDescription("Save VCF").performClick()
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
        composeRule.onNodeWithContentDescription("View contact").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Save VCF").assertIsNotEnabled()
    }
}
