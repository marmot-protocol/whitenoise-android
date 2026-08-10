package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.test.assertIsDisplayed
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

    private fun plural(
        res: Int,
        quantity: Int,
        vararg args: Any,
    ): String =
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .resources
            .getQuantityString(res, quantity, *args)

    @Test
    fun showsCountAndCloseOnly() {
        var closes = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageSelectionBar(
                    count = 3,
                    onClose = { closes++ },
                )
            }
        }

        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(
                plural(R.plurals.message_selected_count, 3, 3),
            ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.close)).performClick()

        assertEquals(1, closes)
    }
}
