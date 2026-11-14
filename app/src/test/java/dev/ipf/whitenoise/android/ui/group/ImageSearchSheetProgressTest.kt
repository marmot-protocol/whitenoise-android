@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.ui.common.SheetTerminalMotion
import dev.ipf.whitenoise.android.ui.common.rememberSheetTerminalMotion
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun parentUploadAndRemovalDoNotReplaceTheAcceptedPickerPresentation() {
        val originalUrl = "https://example.com/original.jpg"
        val parentUrl = mutableStateOf(originalUrl)
        val busy = mutableStateOf(true)
        lateinit var terminal: SheetTerminalMotion
        composeRule.setContent {
            WhiteNoiseTheme {
                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                terminal = rememberSheetTerminalMotion(sheet)
                ImageSearchSheet(
                    initialUrl = parentUrl.value,
                    hasCurrentImage = parentUrl.value.isNotBlank(),
                    header = "Edit image",
                    title = "Test image",
                    seed = "test",
                    urlLabel = "Image URL",
                    removeImageLabel = "Remove test image",
                    applyInFlight = busy.value,
                    onDismiss = {},
                    onApply = {},
                    suppliedSheetState = sheet,
                    suppliedTerminal = terminal,
                )
            }
        }
        composeRule.runOnIdle {
            terminal.closeWhenReady({ !busy.value }) {}
            parentUrl.value = "https://example.com/new-cold-cache.jpg"
        }
        composeRule.onNodeWithTag("image_search.url").assertTextContains(originalUrl)
        composeRule.runOnIdle { parentUrl.value = "" }
        composeRule.onNodeWithTag("image_search.url").assertTextContains(originalUrl)
        composeRule.onNodeWithText("Remove test image").assertExists()
        // Busy clears while exit is accepted, without changing the session's presentation.
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { busy.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("image_search.url").assertTextContains(originalUrl)
        composeRule.onNodeWithText("Remove test image").assertExists()
        composeRule.mainClock.advanceTimeBy(1000)
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
