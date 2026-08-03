package dev.ipf.whitenoise.android.ui.group

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class GroupRosterLoadStatusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingRosterDoesNotRenderAuthoritativeZeroCount() {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupRosterLoadStatus(
                    state = GroupRosterLoadState.LOADING,
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Members").assertIsDisplayed()
        composeRule.onNodeWithText("0 members").assertDoesNotExist()
    }

    @Test
    fun failedRosterShowsRetryAction() {
        var retried = false
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupRosterLoadStatus(
                    state = GroupRosterLoadState.FAILED,
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText("Couldn't load conversation").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
        composeRule.onNodeWithText("0 members").assertDoesNotExist()
    }
}
