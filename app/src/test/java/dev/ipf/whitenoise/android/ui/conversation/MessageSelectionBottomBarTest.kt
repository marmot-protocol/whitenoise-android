package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
class MessageSelectionBottomBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(res: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

    @Test
    fun routesPrimaryActionsAndDelete() {
        var copies = 0
        var forwards = 0
        var deletes = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageSelectionBottomBar(
                    availability =
                        BatchSelectionActionAvailability(
                            canCopy = true,
                            canForward = true,
                            canSave = false,
                            canReply = false,
                            canInfo = false,
                            canDelete = true,
                        ),
                    onCopy = { copies++ },
                    onForward = { forwards++ },
                    onSave = {},
                    onReply = {},
                    onInfo = {},
                    onDelete = { deletes++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.copy)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.forward)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.message_selection_action_delete)).performClick()

        assertEquals(1, copies)
        assertEquals(1, forwards)
        assertEquals(1, deletes)
    }

    @Test
    fun disablesUnavailableActions() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageSelectionBottomBar(
                    availability =
                        BatchSelectionActionAvailability(
                            canCopy = false,
                            canForward = false,
                            canSave = false,
                            canReply = false,
                            canInfo = false,
                            canDelete = true,
                        ),
                    onCopy = {},
                    onForward = {},
                    onSave = {},
                    onReply = {},
                    onInfo = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.copy)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.forward)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.message_selection_action_delete)).assertIsDisplayed()
    }

    @Test
    fun copyActionShowsItsLabelOnLongPress() {
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageSelectionBottomBar(
                    availability =
                        BatchSelectionActionAvailability(
                            canCopy = true,
                            canForward = false,
                            canSave = false,
                            canReply = false,
                            canInfo = true,
                            canDelete = true,
                        ),
                    onCopy = {},
                    onForward = {},
                    onSave = {},
                    onReply = {},
                    onInfo = {},
                    onDelete = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(string(R.string.copy))
            .performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.copy)).assertIsDisplayed()
    }

    @Test
    fun narrowWidthShowsEveryActionDirectlyWithoutOverflowMenu() {
        var saves = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageSelectionBottomBar(
                    modifier = Modifier.width(240.dp),
                    availability =
                        BatchSelectionActionAvailability(
                            canCopy = true,
                            canForward = true,
                            canSave = true,
                            canReply = true,
                            canInfo = true,
                            canDelete = true,
                        ),
                    onCopy = {},
                    onForward = {},
                    onSave = { saves++ },
                    onReply = {},
                    onInfo = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.copy)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.forward)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.reply)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.info)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.shared_media_save)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.message_selection_action_delete)).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(string(R.string.actions)).assertCountEquals(0)

        assertEquals(1, saves)
    }
}
