package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
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

    /** Notices sit under their first letter, and a lowercase name joins its capital's section. */
    @Test
    fun listGroupsNoticesUnderLetterHeadings() {
        val notices =
            listOf(
                OpenSourceNotice("activity", "Activity", "Apache"),
                OpenSourceNotice("errorprone", "error-prone annotations", "Apache"),
                OpenSourceNotice("error", "Error Prone", "Apache"),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                OpenSourceLicensesContent(Result.success(notices), null, {}, {})
            }
        }
        composeRule.onNodeWithText("A").assertIsDisplayed()
        composeRule.onAllNodesWithText("E").assertCountEquals(1)
        composeRule.onNodeWithText("error-prone annotations").assertIsDisplayed()
    }

    /** A section is the uppercase first letter, or "#" for a digit, symbol or empty name. */
    @Test
    fun sectionLetterIsUppercaseOrHash() {
        assertEquals("E", noticeSection("error-prone annotations"))
        assertEquals("#", noticeSection("2D library"))
        assertEquals("#", noticeSection(""))
    }

    /** Selecting a row forwards the full notice identity to the screen state. */
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

    /** The reader exposes the complete text, including its copyright prefix. */
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

    /** The actual generated catalogue must reach the selectable reader without losing full notice text. */
    @Test
    fun generatedFirebaseNoticeOpensWithItsCompleteText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notices = readOpenSourceNotices(context.resources)
        val notice = notices.first { it.name == "Firebase" }
        var selected by mutableStateOf<OpenSourceNotice?>(null)
        composeRule.setContent {
            WhiteNoiseTheme {
                OpenSourceLicensesContent(Result.success(notices), selected, { selected = it }, {})
            }
        }
        composeRule.onNodeWithTag("licenses.list").performScrollToNode(hasText(notice.name))
        composeRule.onNodeWithText(notice.name).performClick()
        composeRule.onNodeWithTag("licenses.text").assertTextEquals(notice.text)
    }

    /** Bad attribution remains a visible retry state instead of appearing successfully empty. */
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
