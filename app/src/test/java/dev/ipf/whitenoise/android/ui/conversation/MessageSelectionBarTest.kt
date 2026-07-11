package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageSelectionBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(res: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

    @Test
    fun showsCountAndRoutesAvailableActions() {
        var closes = 0
        var copies = 0
        var forwards = 0
        var deletes = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageSelectionBar(
                    count = 3,
                    canCopy = true,
                    canForward = true,
                    onClose = { closes++ },
                    onCopy = { copies++ },
                    onForward = { forwards++ },
                    onDelete = { deletes++ },
                )
            }
        }

        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.close)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.copy)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.forward)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.delete)).performClick()

        assertEquals(1, closes)
        assertEquals(1, copies)
        assertEquals(1, forwards)
        assertEquals(1, deletes)
    }

    @Test
    fun disablesCopyAndForwardWhenSelectionHasNoEligibleText() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageSelectionBar(
                    count = 2,
                    canCopy = false,
                    canForward = false,
                    onClose = {},
                    onCopy = {},
                    onForward = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.copy)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.forward)).assertIsNotEnabled()
    }
}
