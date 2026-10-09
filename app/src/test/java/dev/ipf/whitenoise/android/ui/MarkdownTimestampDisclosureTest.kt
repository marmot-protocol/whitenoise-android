package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkdownTimestampDisclosureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val token = "<t:0:R>"
    private val disclosure = markdownTimestampAbsolute(0, 'F') + "\n" + token
    private val dismiss = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.dismiss)

    @Test
    fun disclosureSurvivesRefresh() {
        val text = mutableStateOf(timestampText("10 seconds ago"))
        composeRule.setContent { WhiteNoiseTheme { MarkdownTimestampText(text.value) } }
        composeRule.onNodeWithContentDescription(disclosure).performClick()
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()

        // Equal-length labels keep annotation ranges and cached clock callbacks unchanged.
        composeRule.runOnIdle { text.value = timestampText("11 seconds ago") }
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
        composeRule.onNodeWithText(dismiss).performClick()
        composeRule.runOnIdle { text.value = timestampText("12 seconds ago") }
        composeRule.onNodeWithContentDescription(disclosure).performClick()
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()

        composeRule.runOnIdle { text.value = timestampText("1 minute ago") }
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
    }

    @Test
    fun removedTokenClearsDisclosure() {
        val text = mutableStateOf(timestampText("10 seconds ago"))
        composeRule.setContent { WhiteNoiseTheme { MarkdownTimestampText(text.value) } }
        composeRule.onNodeWithContentDescription(disclosure).performClick()

        composeRule.runOnIdle { text.value = AnnotatedString("replacement message") }
        composeRule.onNodeWithText(disclosure).assertDoesNotExist()
        composeRule.runOnIdle { text.value = timestampText("11 seconds ago") }
        composeRule.onNodeWithText(disclosure).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(disclosure).performClick()
        composeRule.onNodeWithText(disclosure).assertIsDisplayed()
    }

    private fun timestampText(label: String) =
        buildAnnotatedString {
            appendInlineContent("markdown-timestamp-clock:" + token, "◷")
            append(" " + label)
            addStringAnnotation(TIMESTAMP_TAG, token, 0, length)
        }
}
