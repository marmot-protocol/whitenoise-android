package dev.ipf.whitenoise.android.ui.group

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "en-w800dp-h1000dp")
class ImageSearchSheetProgressTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun callbackThatDoesNotStartMutationDoesNotLeaveProgressVisible() {
        var applyCalls = 0
        val applyLabel = "Apply test image"
        composeRule.setContent {
            WhiteNoiseTheme {
                ImageSearchSheet(
                    initialUrl = "https://example.com/image.jpg",
                    header = "Edit image",
                    title = "Test image",
                    seed = "test",
                    urlLabel = "Image URL",
                    applyImageLabel = applyLabel,
                    applyInFlight = false,
                    onDismiss = {},
                    onApply = { applyCalls++ },
                )
            }
        }

        composeRule.onNodeWithText(applyLabel).assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(1, applyCalls)
        }
        composeRule.onNodeWithText(applyLabel).assertIsDisplayed()
    }

    @Test
    fun emojiEntryUsesTheDedicatedChooserCallback() {
        var emojiChooserCalls = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ImageSearchSheet(
                    initialUrl = "",
                    header = "Edit image",
                    title = "Test image",
                    seed = "test",
                    urlLabel = "Image URL",
                    applyInFlight = false,
                    onDismiss = {},
                    onApply = {},
                    onPickEmoji = { emojiChooserCalls++ },
                )
            }
        }

        composeRule.onNodeWithText("Emoji").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, emojiChooserCalls) }
    }
}
