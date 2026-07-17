package dev.ipf.whitenoise.android.ui.conversation.messages

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteBreakdown
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral coverage for the multi-select delete dialog: it offers the
 * for-everyone choice only when the selection carries at least one
 * for-everyone-capable message, an in-flight delete disables the destructive
 * choices (the repeated-tap guard), and it mirrors the single-message dialog's
 * text-button pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class BatchMessageDeleteDialogBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int): String = app.getString(resId)

    private fun renderContent(
        selectedCount: Int,
        breakdown: BatchDeleteBreakdown,
        deleteInFlight: Boolean = false,
        content: @Composable (
            onEveryone: () -> Unit,
            onMe: () -> Unit,
            onCancel: () -> Unit,
        ) -> Unit = { onEveryone, onMe, onCancel ->
            BatchMessageDeleteDialogContent(
                selectedCount = selectedCount,
                breakdown = breakdown,
                deleteInFlight = deleteInFlight,
                onDeleteForEveryone = onEveryone,
                onDeleteForMe = onMe,
                onCancel = onCancel,
            )
        },
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface { content({}, {}, {}) }
            }
        }
    }

    @Test
    fun bothChoicesRenderWhenSelectionHasForEveryoneCapableMessages() {
        renderContent(
            selectedCount = 3,
            breakdown = BatchDeleteBreakdown(deleteForEveryone = 2, hideLocally = 1),
        )
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete_for_me)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
    }

    @Test
    fun onlyLocalChoiceRendersWhenNoMessageCanBeDeletedForEveryone() {
        renderContent(
            selectedCount = 2,
            breakdown = BatchDeleteBreakdown(deleteForEveryone = 0, hideLocally = 2),
        )
        composeRule.onNodeWithText(string(R.string.delete_for_me)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertDoesNotExist()
    }

    @Test
    fun inFlightDisablesDestructiveChoicesButNotCancel() {
        renderContent(
            selectedCount = 3,
            breakdown = BatchDeleteBreakdown(deleteForEveryone = 2, hideLocally = 1),
            deleteInFlight = true,
        )
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.delete_for_me)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsEnabled()
    }

    @Test
    fun repeatedTapsLaunchExactlyOneDelete() {
        var everyoneTaps = 0
        composeRule.setContent {
            // Mirrors the screen wiring: the first tap flips the in-flight flag
            // which disables the option before a second tap can land.
            var inFlight by remember { mutableStateOf(false) }
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    BatchMessageDeleteDialogContent(
                        selectedCount = 2,
                        breakdown = BatchDeleteBreakdown(deleteForEveryone = 2, hideLocally = 0),
                        deleteInFlight = inFlight,
                        onDeleteForEveryone = {
                            if (inFlight) return@BatchMessageDeleteDialogContent
                            everyoneTaps += 1
                            inFlight = true
                        },
                        onDeleteForMe = {},
                        onCancel = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, everyoneTaps)
    }
}
