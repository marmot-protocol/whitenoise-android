package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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

    /** Plural. */
    private fun plural(
        res: Int,
        quantity: Int,
        vararg args: Any,
    ): String =
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .resources
            .getQuantityString(res, quantity, *args)

    /** Shows prototype title and retains selected count state. */
    @Test
    fun showsPrototypeTitleAndRetainsSelectedCountState() {
        var closes = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageSelectionBar(
                    count = 3,
                    onClose = { closes++ },
                )
            }
        }

        composeRule
            .onNodeWithText(string(R.string.conversation_select_messages))
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    plural(R.plurals.message_selected_count, 3, 3),
                ),
            )
        composeRule.onNodeWithContentDescription(string(R.string.close)).performClick()

        assertEquals(1, closes)
    }
}
