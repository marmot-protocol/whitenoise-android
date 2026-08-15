package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationMembershipPendingBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unresolvedRosterShowsVisibleProgressInsteadOfBlankSpace() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationMembershipPendingBar(
                    rosterState = GroupRosterLoadState.LOADING,
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag(CONVERSATION_MEMBERSHIP_PENDING_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Checking conversation access…").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun failedRosterOffersRetry() {
        var retried = false
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationMembershipPendingBar(
                    rosterState = GroupRosterLoadState.FAILED,
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText("Couldn’t verify access").assertIsDisplayed()
        composeRule.onNodeWithTag(CONVERSATION_MEMBERSHIP_RETRY_TAG).performClick()
        assertTrue(retried)
    }
}
