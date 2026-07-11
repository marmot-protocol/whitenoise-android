package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class ChatListSelectionBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(res: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

    @Test
    fun showsCountAndRoutesActions() {
        var closes = 0
        var archives = 0
        var deletes = 0
        var selectAll = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionBar(
                    count = 2,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMuteToggle = false,
                    muted = false,
                    onClose = { closes++ },
                    onArchive = { archives++ },
                    onDelete = { deletes++ },
                    onMarkRead = {},
                    onMuteToggle = {},
                    onSelectAll = { selectAll++ },
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.close)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.archive)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.delete)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.actions)).performClick()
        composeRule.onNodeWithText(string(R.string.chat_list_select_all)).performClick()

        assertEquals(1, closes)
        assertEquals(1, archives)
        assertEquals(1, deletes)
        assertEquals(1, selectAll)
    }

    @Test
    fun showsSelectAllWhenNotAllVisibleSelected() {
        var selectAll = 0
        var deselectAll = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionBar(
                    count = 1,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMuteToggle = false,
                    muted = false,
                    onClose = {},
                    onArchive = {},
                    onDelete = {},
                    onMarkRead = {},
                    onMuteToggle = {},
                    onSelectAll = { selectAll++ },
                    onDeselectAll = { deselectAll++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.actions)).performClick()
        composeRule.onNodeWithText(string(R.string.chat_list_select_all)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_list_deselect_all)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_list_select_all)).performClick()

        assertEquals(1, selectAll)
        assertEquals(0, deselectAll)
    }

    @Test
    fun showsDeselectAllWhenAllVisibleSelected() {
        var selectAll = 0
        var deselectAll = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionBar(
                    count = 2,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = true,
                    showMarkRead = false,
                    showMuteToggle = false,
                    muted = false,
                    onClose = {},
                    onArchive = {},
                    onDelete = {},
                    onMarkRead = {},
                    onMuteToggle = {},
                    onSelectAll = { selectAll++ },
                    onDeselectAll = { deselectAll++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.actions)).performClick()
        composeRule.onNodeWithText(string(R.string.chat_list_deselect_all)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_list_select_all)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_list_deselect_all)).performClick()

        assertEquals(0, selectAll)
        assertEquals(1, deselectAll)
    }

    @Test
    fun disablesActionsWhenNothingSelected() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionBar(
                    count = 0,
                    archiveAction = ChatListBulkArchiveAction.Unarchive,
                    actionsEnabled = false,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMuteToggle = false,
                    muted = false,
                    onClose = {},
                    onArchive = {},
                    onDelete = {},
                    onMarkRead = {},
                    onMuteToggle = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.unarchive)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.delete)).assertIsNotEnabled()
    }

    @Test
    fun singleSelectionOverflowRoutesMarkReadAndMute() {
        var markRead = 0
        var muteToggle = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionBar(
                    count = 1,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = true,
                    showMuteToggle = true,
                    muted = false,
                    onClose = {},
                    onArchive = {},
                    onDelete = {},
                    onMarkRead = { markRead++ },
                    onMuteToggle = { muteToggle++ },
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.actions)).performClick()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_read)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mute)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_unmute)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_read)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.actions)).performClick()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mute)).performClick()

        assertEquals(1, markRead)
        assertEquals(1, muteToggle)
    }

    @Test
    fun multiSelectionOverflowHidesSingleChatActions() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionBar(
                    count = 2,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMuteToggle = false,
                    muted = false,
                    onClose = {},
                    onArchive = {},
                    onDelete = {},
                    onMarkRead = {},
                    onMuteToggle = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.actions)).performClick()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_read)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mute)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_row_action_unmute)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_list_select_all)).assertIsDisplayed()
    }

    @Test
    fun singleSelectionShowsUnmuteWhenMuted() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionBar(
                    count = 1,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMuteToggle = true,
                    muted = true,
                    onClose = {},
                    onArchive = {},
                    onDelete = {},
                    onMarkRead = {},
                    onMuteToggle = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.actions)).performClick()
        composeRule.onNodeWithText(string(R.string.chat_row_action_unmute)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mute)).assertDoesNotExist()
    }
}
