package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shared top bar's leading control: its label matches its icon, and it can be disabled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhiteNoiseTopBarNavigationTest {
    @get:Rule val rule = createComposeRule()

    /** A pushed page leads with Back, which runs the page's callback. */
    @Test
    fun backIsLabelledBackAndRunsTheCallback() {
        var backs = 0
        rule.setContent { WhiteNoiseTheme { WhiteNoiseTopBar(title = "Page", onBack = { backs++ }) } }
        rule.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Close").assertDoesNotExist()
        assertEquals(1, backs)
    }

    /** A modal flow leads with Close, which runs the same callback. */
    @Test
    fun closeIsLabelledCloseAndRunsTheCallback() {
        var closes = 0
        rule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseTopBar(
                    title = "Flow",
                    onBack = { closes++ },
                    navigation = WhiteNoiseTopBarNavigation.Close,
                )
            }
        }
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
        assertEquals(1, closes)
    }

    /** A page that is committing work disables the control so it cannot leave mid-commit. */
    @Test
    fun disabledNavigationIgnoresTaps() {
        var backs = 0
        rule.setContent {
            WhiteNoiseTheme { WhiteNoiseTopBar(title = "Page", onBack = { backs++ }, navigationEnabled = false) }
        }
        rule.onNodeWithTag(WHITE_NOISE_TOP_BAR_BACK_TAG).assertIsNotEnabled().performClick()
        assertEquals(0, backs)
    }

    /** The accessory follows the leading control inside the same navigation slot. */
    @Test
    fun accessoryRendersBesideTheControl() {
        rule.setContent {
            WhiteNoiseTheme {
                WhiteNoiseTopBar(title = "Flow", onBack = {}, navigationAccessory = { Text("Sending as Alice") })
            }
        }
        rule.onNodeWithText("Sending as Alice").assertExists()
    }
}
