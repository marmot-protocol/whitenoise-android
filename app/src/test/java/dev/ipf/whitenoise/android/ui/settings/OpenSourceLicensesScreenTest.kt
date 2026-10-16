package dev.ipf.whitenoise.android.ui.settings

import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenSourceLicensesScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun productionRetryAndBothBackPathsRetainTheLicenseList() {
        val reads = AtomicInteger()
        var parentReturns = 0
        val notice = OpenSourceNotice("notice", "Example library", "Full notice body")
        val loader: suspend (Resources) -> List<OpenSourceNotice> = {
            if (reads.incrementAndGet() == 1) error("Controlled resource failure")
            listOf(notice)
        }
        composeRule.setContent {
            WhiteNoiseTheme { OpenSourceLicensesScreen({ parentReturns++ }, loader) }
        }
        waitFor("licenses.failed")
        composeRule.onNodeWithText("Retry").performClick()
        waitFor("licenses.list")
        assertEquals(2, reads.get())
        composeRule.onNodeWithText(notice.name).performClick()
        composeRule.onNodeWithTag("licenses.text").assertIsDisplayed()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("licenses.list").assertIsDisplayed()
        assertEquals(0, parentReturns)
        composeRule.onNodeWithText(notice.name).performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithTag("licenses.list").assertIsDisplayed()
        assertEquals(0, parentReturns)
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, parentReturns)
        assertEquals(2, reads.get())
    }

    @Test
    fun aChangedLoaderWaitsForExplicitRetryAfterFailure() {
        val reads = AtomicInteger()
        val notice = OpenSourceNotice("notice", "Example library", "Full notice body")
        var loader by
            mutableStateOf<suspend (Resources) -> List<OpenSourceNotice>>(
                {
                    reads.incrementAndGet()
                    error("Controlled resource failure")
                },
            )
        composeRule.setContent {
            WhiteNoiseTheme { OpenSourceLicensesScreen({}, loader) }
        }
        waitFor("licenses.failed")
        composeRule.runOnIdle {
            loader = {
                reads.incrementAndGet()
                listOf(notice)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("licenses.failed").assertIsDisplayed()
        assertEquals(1, reads.get())
        composeRule.onNodeWithText("Retry").performClick()
        waitFor("licenses.list")
        composeRule.onNodeWithText(notice.name).assertIsDisplayed()
        assertEquals(2, reads.get())
    }

    private fun waitFor(tag: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }
}
