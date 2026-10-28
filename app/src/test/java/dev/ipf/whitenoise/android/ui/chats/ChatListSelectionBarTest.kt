package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
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

    /** Hides the count and routes accessible icon actions. */
    @Test
    fun hidesCountAndRoutesIconActions() {
        var closes = 0
        var archives = 0
        var deletes = 0
        var selectAll = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    ChatListSelectionBar(onClose = { closes++ })
                    ChatListSelectionControls(
                        count = 2,
                        archiveAction = ChatListBulkArchiveAction.Archive,
                        actionsEnabled = true,
                        allVisibleSelected = false,
                        showMarkRead = false,
                        showMarkUnread = false,
                        showMovePinnedUp = false,
                        showMovePinnedDown = false,
                        onArchive = { archives++ },
                        onDelete = { deletes++ },
                        onAddToFolder = {},
                        onMarkRead = {},
                        onMarkUnread = {},
                        onSetMuted = {},
                        onSetPinned = {},
                        onMovePinned = {},
                        onSelectAll = { selectAll++ },
                        onDeselectAll = {},
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selectedDescription = context.resources.getQuantityString(R.plurals.chat_list_selected_count, 2, 2)
        composeRule.onNodeWithContentDescription(selectedDescription).assertIsDisplayed()
        composeRule.onNodeWithText("2").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.close)).performClick()
        composeRule.onAllNodesWithContentDescription(string(R.string.actions)).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(string(R.string.archive)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.delete)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_select_all)).performClick()

        assertEquals(1, closes)
        assertEquals(1, archives)
        assertEquals(1, deletes)
        assertEquals(1, selectAll)
    }

    /**
     * #3310: bulk abandonment is its own destructive action beside Leave and delete, runs only while the selection is
     * still current, and is absent when the selection does not offer it.
     */
    @Test
    fun abandonActionIsSeparateFromDeleteAndFencedByTheSelection() {
        var deletes = 0
        var abandons = 0
        var current = true
        val offered = mutableStateOf<String?>(string(R.string.chats_abandon_action))
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 2,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMarkUnread = false,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onArchive = {},
                    onDelete = { deletes++ },
                    onAddToFolder = {},
                    onMarkRead = {},
                    onMarkUnread = {},
                    onSetMuted = {},
                    onSetPinned = {},
                    onMovePinned = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                    isCurrent = { current },
                    deleteLabel = string(R.string.leave_and_delete),
                    abandonLabel = offered.value,
                    onAbandon = { abandons++ },
                )
            }
        }
        val abandon = string(R.string.chats_abandon_action)
        composeRule.onNodeWithContentDescription(abandon).performClick()
        assertEquals(1, abandons)
        assertEquals("abandoning never leaves or deletes", 0, deletes)
        current = false
        composeRule.onNodeWithContentDescription(abandon).performClick()
        assertEquals("a replaced selection submits nothing", 1, abandons)
        offered.value = null
        composeRule.onNodeWithContentDescription(abandon).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.leave_and_delete)).assertIsDisplayed()
    }

    /** Shows select all when not all visible selected. */
    @Test
    fun showsSelectAllWhenNotAllVisibleSelected() {
        var selectAll = 0
        var deselectAll = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 1,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMarkUnread = false,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onArchive = {},
                    onDelete = {},
                    onAddToFolder = {},
                    onMarkRead = {},
                    onMarkUnread = {},
                    onSetMuted = {},
                    onSetPinned = {},
                    onMovePinned = {},
                    onSelectAll = { selectAll++ },
                    onDeselectAll = { deselectAll++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.chat_list_select_all)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_select_all)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_deselect_all)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_select_all)).performClick()

        composeRule.onNodeWithText(string(R.string.chat_list_select_all)).assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(string(R.string.chat_list_select_all))
            .performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.chat_list_select_all)).assertIsDisplayed()

        assertEquals(1, selectAll)
        assertEquals(0, deselectAll)
    }

    /** Shows deselect all when all visible selected. */
    @Test
    fun showsDeselectAllWhenAllVisibleSelected() {
        var selectAll = 0
        var deselectAll = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 2,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = true,
                    showMarkRead = false,
                    showMarkUnread = false,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onArchive = {},
                    onDelete = {},
                    onAddToFolder = {},
                    onMarkRead = {},
                    onMarkUnread = {},
                    onSetMuted = {},
                    onSetPinned = {},
                    onMovePinned = {},
                    onSelectAll = { selectAll++ },
                    onDeselectAll = { deselectAll++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.chat_list_deselect_all)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_deselect_all)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_select_all)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_deselect_all)).performClick()

        assertEquals(0, selectAll)
        assertEquals(1, deselectAll)
    }

    /** Disables actions when nothing selected. */
    @Test
    fun disablesActionsWhenNothingSelected() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 0,
                    archiveAction = ChatListBulkArchiveAction.Unarchive,
                    actionsEnabled = false,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMarkUnread = false,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onArchive = {},
                    onDelete = {},
                    onAddToFolder = {},
                    onMarkRead = {},
                    onMarkUnread = {},
                    onSetMuted = {},
                    onSetPinned = {},
                    onMovePinned = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.unarchive)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_select_all)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(string(R.string.delete)).assertIsNotEnabled()
    }

    /** Direct single-selection actions route mark read and mute. */
    @Test
    fun singleSelectionDirectActionsRouteMarkReadAndMute() {
        var markRead = 0
        var muteToggle = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 1,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = true,
                    showMarkUnread = false,
                    showMute = true,
                    showUnmute = false,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onArchive = {},
                    onDelete = {},
                    onAddToFolder = {},
                    onMarkRead = { markRead++ },
                    onMarkUnread = {},
                    onSetMuted = { muteToggle++ },
                    onSetPinned = {},
                    onMovePinned = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.chat_list_select_all)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_mark_read)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_mute)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_unmute)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_mark_read)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_mute)).performClick()

        assertEquals(1, markRead)
        assertEquals(1, muteToggle)
    }

    /** Pinned selection offers unpin and manual moves. */
    @Test
    fun pinnedSelectionOffersUnpinAndManualMoves() {
        var pinToggles = 0
        val moves = mutableListOf<Int>()
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 1,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMarkUnread = false,
                    showPin = false,
                    showUnpin = true,
                    showMovePinnedUp = true,
                    showMovePinnedDown = true,
                    onArchive = {},
                    onDelete = {},
                    onAddToFolder = {},
                    onMarkRead = {},
                    onMarkUnread = {},
                    onSetMuted = {},
                    onSetPinned = { pinToggles++ },
                    onMovePinned = { moves += it },
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_unpin)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_pin)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_move_up)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_move_down)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_unpin)).performClick()

        assertEquals(listOf(-1, 1), moves)
        assertEquals(1, pinToggles)
    }

    /** Mixed multi-selection offers separately named explicit desired states. */
    @Test
    @Suppress("LongMethod") // Keep the explicit mixed-action fixture beside its paired-label routing assertions.
    fun mixedMultiSelectionRoutesExplicitDesiredStates() {
        val mutedTargets = mutableListOf<Boolean>()
        val pinnedTargets = mutableListOf<Boolean>()
        val folderTargets = mutableListOf<String>()
        val deleteTargets = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 2,
                    deleteLabel = string(R.string.leave_and_delete),
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = true,
                    showMarkUnread = true,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onArchive = {},
                    onDelete = { deleteTargets += "leave" },
                    onAddToFolder = { folderTargets += "add" },
                    onMarkRead = {},
                    onMarkUnread = {},
                    onMovePinned = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                    showMute = true,
                    showUnmute = true,
                    showPin = true,
                    showUnpin = true,
                    onSetMuted = { mutedTargets += it },
                    onSetPinned = { pinnedTargets += it },
                    onExcludeFromFolder = { folderTargets += "exclude" },
                    onDeleteLocal = { deleteTargets += "local" },
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_mark_read)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_mute)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_unmute)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.archive)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_action_add_to_folder)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.leave_and_delete)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_select_all)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_mute)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_unmute)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_pin)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_unpin)).performClick()
        val pairedLabels =
            listOf(
                R.string.chat_list_action_add_to_folder,
                R.string.folder_exclude_chat,
                R.string.delete_from_device,
                R.string.leave_and_delete,
            )
        for (label in pairedLabels) {
            composeRule.onNodeWithText(string(label)).assertDoesNotExist()
            composeRule.onNodeWithContentDescription(string(label)).assertIsDisplayed().performClick()
        }
        assertEquals(listOf("add", "exclude"), folderTargets)
        assertEquals(listOf("local", "leave"), deleteTargets)
        assertEquals(listOf(true, false), mutedTargets)
        assertEquals(listOf(true, false), pinnedTargets)
    }

    /** Direct multi-selection bar routes add to folder. */
    @Test
    fun directBarRoutesAddToFolderForMultiSelection() {
        var addToFolder = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 2,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMarkUnread = false,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onArchive = {},
                    onDelete = {},
                    onAddToFolder = { addToFolder++ },
                    onMarkRead = {},
                    onMarkUnread = {},
                    onSetMuted = {},
                    onSetPinned = {},
                    onMovePinned = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.chat_list_action_add_to_folder)).performClick()

        assertEquals(1, addToFolder)
    }

    /** Direct single-selection bar retains add to folder. */
    @Test
    fun directBarShowsAddToFolderForSingleSelection() {
        var addToFolder = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 1,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = true,
                    showMarkUnread = false,
                    showMute = true,
                    showUnmute = false,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onArchive = {},
                    onDelete = {},
                    onAddToFolder = { addToFolder++ },
                    onMarkRead = {},
                    onMarkUnread = {},
                    onSetMuted = {},
                    onSetPinned = {},
                    onMovePinned = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.chat_list_action_add_to_folder)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_list_action_add_to_folder)).performClick()

        assertEquals(1, addToFolder)
    }

    /** Single selection shows unmute when muted. */
    @Test
    fun singleSelectionShowsUnmuteWhenMuted() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListSelectionControls(
                    count = 1,
                    archiveAction = ChatListBulkArchiveAction.Archive,
                    actionsEnabled = true,
                    allVisibleSelected = false,
                    showMarkRead = false,
                    showMarkUnread = false,
                    showMute = false,
                    showUnmute = true,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onArchive = {},
                    onDelete = {},
                    onAddToFolder = {},
                    onMarkRead = {},
                    onMarkUnread = {},
                    onSetMuted = {},
                    onSetPinned = {},
                    onMovePinned = {},
                    onSelectAll = {},
                    onDeselectAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_unmute)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.chat_row_action_mute)).assertDoesNotExist()
    }
}
