package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenSourceLicensesContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listOpensTheSelectedNotice() {
        val notice = OpenSourceNotice("notice", "Example library", "Copyright Example\nFull licence text")
        var selected: OpenSourceNotice? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                OpenSourceLicensesContent(Result.success(listOf(notice)), null, { selected = it }, {})
            }
        }
        composeRule.onNodeWithText(notice.name).performClick()
        assertEquals(notice, selected)
    }

    @Test
    fun selectedFullTextIsVisible() {
        val notice = OpenSourceNotice("notice", "Example library", "Copyright Example\nFull licence text")
        composeRule.setContent {
            WhiteNoiseTheme {
                OpenSourceLicensesContent(Result.success(listOf(notice)), notice, {}, {})
            }
        }
        composeRule.onNodeWithText(notice.text).assertIsDisplayed()
    }

    @Test
    fun failedMetadataRemainsAnActionableRetryInsteadOfAnEmptyList() {
        var retries = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                OpenSourceLicensesContent(Result.failure(IllegalArgumentException()), null, {}, { retries++ })
            }
        }
        composeRule.onNodeWithTag("licenses.failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
}
