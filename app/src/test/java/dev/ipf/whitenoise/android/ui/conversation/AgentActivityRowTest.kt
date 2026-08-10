package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.ipf.whitenoise.android.core.AgentActivityPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentActivityRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun intermediaryCommentaryIsVisibleInTheConversation() {
        composeRule.setContent {
            WhiteNoiseTheme {
                AgentActivityRow(
                    activity =
                        AgentActivityPresentation(
                            text = "Checking the review comments.",
                            status = "commentary",
                        ),
                )
            }
        }

        composeRule.onNodeWithText("Checking the review comments.").assertIsDisplayed()
        composeRule.onNodeWithText("Commentary").assertIsDisplayed()
    }

    @Test
    fun longPressRequestsMessageDeletion() {
        var deletionRequested = false
        composeRule.setContent {
            WhiteNoiseTheme {
                AgentActivityRow(
                    activity = AgentActivityPresentation(text = "Still working.", status = "commentary"),
                    onRequestDelete = { deletionRequested = true },
                    modifier = Modifier.testTag("agent-activity"),
                )
            }
        }

        composeRule.onNodeWithTag("agent-activity").performTouchInput { longClick() }

        assertTrue(deletionRequested)
    }

    @Test
    fun tapRequestsMessageDeletion() {
        var deletionRequested = false
        composeRule.setContent {
            WhiteNoiseTheme {
                AgentActivityRow(
                    activity = AgentActivityPresentation(text = "Still working.", status = "commentary"),
                    onRequestDelete = { deletionRequested = true },
                    modifier = Modifier.testTag("agent-activity"),
                )
            }
        }

        composeRule.onNodeWithTag("agent-activity").performClick()

        assertTrue(deletionRequested)
    }

    @Test
    fun senderAvatarExposesLabeledProfileAction() {
        var profileRequested = false
        composeRule.setContent {
            WhiteNoiseTheme {
                AgentActivityRow(
                    activity = AgentActivityPresentation(text = "Still working.", status = "commentary"),
                    sender = AgentOperationSenderPresentation(name = "Agent", seed = "agent", avatarUrl = null),
                    onSenderClick = { profileRequested = true },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Open profile: Agent")
            .assertHasClickAction()
            .performClick()

        assertTrue(profileRequested)
    }
}
