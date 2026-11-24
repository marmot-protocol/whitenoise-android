package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SectionHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun plainHeaderExposesHeadingRole() {
        composeRule.setContent { WhiteNoiseTheme { SectionHeader("Contacts", Modifier.testTag("header")) } }

        composeRule.onNodeWithTag("header").assertIsDisplayed().assert(isHeading)
    }

    @Test
    fun headerWithTrailingActionKeepsHeadingOnTheLabel() {
        composeRule.setContent {
            WhiteNoiseTheme {
                SectionHeader("Recent") { Text("Clear", Modifier.testTag("action")) }
            }
        }

        composeRule
            .onNodeWithTag("action")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("Recent").assertIsDisplayed().assert(isHeading)
    }
}
