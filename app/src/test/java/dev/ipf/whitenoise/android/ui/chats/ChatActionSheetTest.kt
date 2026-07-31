package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class ChatActionSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun string(res: Int): String = context.getString(res)

    @Test
    fun rendersInverseActionsAndRoutesSelectionAfterDismissing() {
        var dismisses = 0
        var selects = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatActionSheet(
                    hasUnread = true,
                    canMarkUnread = true,
                    archived = true,
                    muted = true,
                    onMarkRead = {},
                    onMarkUnread = {},
                    onAddToFolder = {},
                    onArchiveToggle = {},
                    onMuteToggle = {},
                    onSelect = { selects++ },
                    onDelete = {},
                    onDismiss = { dismisses++ },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_read)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_unread)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_list_action_add_to_folder)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_unarchive)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_unmute)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.select)).performClick()

        assertEquals(1, dismisses)
        assertEquals(1, selects)
    }

    @Test
    fun omitsUnreadActionWhenMembershipCannotPersistIt() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatActionSheet(
                    hasUnread = false,
                    canMarkUnread = false,
                    archived = false,
                    muted = false,
                    onMarkRead = {},
                    onMarkUnread = {},
                    onAddToFolder = {},
                    onArchiveToggle = {},
                    onMuteToggle = {},
                    onSelect = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_read)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_unread)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_row_action_archive)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mute)).assertIsDisplayed()
    }
}
