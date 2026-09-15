package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatActionSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Resolves a string resource in the test context. */
    private fun string(res: Int): String = context.getString(res)

    /** Renders inverse actions and routes selection after dismissing. */
    @Test
    fun rendersInverseActionsAndRoutesSelectionAfterDismissing() {
        var dismisses = 0
        var selects = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatContextMenu(
                    hasUnread = true,
                    canMarkUnread = true,
                    archived = true,
                    muted = true,
                    pinned = false,
                    showPinToggle = false,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onMarkRead = {},
                    onMarkUnread = {},
                    onAddToFolder = {},
                    onArchiveToggle = {},
                    onMuteToggle = {},
                    onPinToggle = {},
                    onMovePinned = {},
                    onSelect = { selects++ },
                    onDelete = {},
                    onDismiss = { dismisses++ },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_read)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_unread)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_list_action_add_to_folder)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_pin)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_row_action_unpin)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_row_action_unarchive)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_unmute)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.select)).performClick()

        assertEquals(1, dismisses)
        assertEquals(1, selects)
    }

    /** Omits unread action when membership cannot persist it. */
    @Test
    fun omitsUnreadActionWhenMembershipCannotPersistIt() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatContextMenu(
                    hasUnread = false,
                    canMarkUnread = false,
                    archived = false,
                    muted = false,
                    pinned = false,
                    showPinToggle = true,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onMarkRead = {},
                    onMarkUnread = {},
                    onAddToFolder = {},
                    onArchiveToggle = {},
                    onMuteToggle = {},
                    onPinToggle = {},
                    onMovePinned = {},
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
        composeRule.onNodeWithText(string(R.string.chat_row_action_pin)).assertIsDisplayed()
    }

    /** Renders pinned actions and routes move after dismissing. */
    @Test
    fun rendersPinnedActionsAndRoutesMoveAfterDismissing() {
        var dismisses = 0
        var moveDelta = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatContextMenu(
                    hasUnread = false,
                    canMarkUnread = true,
                    archived = false,
                    muted = false,
                    pinned = true,
                    showPinToggle = true,
                    showMovePinnedUp = true,
                    showMovePinnedDown = true,
                    onMarkRead = {},
                    onMarkUnread = {},
                    onAddToFolder = {},
                    onArchiveToggle = {},
                    onMuteToggle = {},
                    onPinToggle = {},
                    onMovePinned = { moveDelta = it },
                    onSelect = {},
                    onDelete = {},
                    onDismiss = { dismisses++ },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.chat_row_action_unpin)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_move_up)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_move_down)).performScrollTo().performClick()

        assertEquals(1, dismisses)
        assertEquals(1, moveDelta)
    }

    /** Routes pin after dismissing. */
    @Test
    fun routesPinAfterDismissing() {
        var dismisses = 0
        var pins = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatContextMenu(
                    hasUnread = false,
                    canMarkUnread = true,
                    archived = false,
                    muted = false,
                    pinned = false,
                    showPinToggle = true,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onMarkRead = {},
                    onMarkUnread = {},
                    onAddToFolder = {},
                    onArchiveToggle = {},
                    onMuteToggle = {},
                    onPinToggle = { pins++ },
                    onMovePinned = {},
                    onSelect = {},
                    onDelete = {},
                    onDismiss = { dismisses++ },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.chat_row_action_pin)).performClick()

        assertEquals(1, dismisses)
        assertEquals(1, pins)
    }

    /** Compact large text sheet can scroll to the destructive action. */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h320dp-mdpi")
    fun compactLargeTextSheetCanScrollToTheDestructiveAction() {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    Box {
                        ChatContextMenu(
                            hasUnread = false,
                            canMarkUnread = true,
                            archived = false,
                            muted = false,
                            pinned = true,
                            showPinToggle = true,
                            showMovePinnedUp = true,
                            showMovePinnedDown = true,
                            onMarkRead = {},
                            onMarkUnread = {},
                            onAddToFolder = {},
                            onArchiveToggle = {},
                            onMuteToggle = {},
                            onPinToggle = {},
                            onMovePinned = {},
                            onSelect = {},
                            onDelete = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        composeRule
            .onNodeWithText(string(R.string.delete))
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Action sheet light screenshot. */
    @Test
    fun actionSheetLightScreenshot() {
        renderScreenshotSheet(darkTheme = false)
        composeRule
            .onNode(isPopup(), useUnmergedTree = true)
            .captureRoboImage("src/test/snapshots/chat_action_sheet_light.png")
    }

    /** Action sheet dark screenshot. */
    @Test
    fun actionSheetDarkScreenshot() {
        renderScreenshotSheet(darkTheme = true)
        composeRule
            .onNode(isPopup(), useUnmergedTree = true)
            .captureRoboImage("src/test/snapshots/chat_action_sheet_dark.png")
    }

    /** Composes the sheet for a screenshot capture. */
    private fun renderScreenshotSheet(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                ChatContextMenu(
                    hasUnread = false,
                    canMarkUnread = true,
                    archived = false,
                    muted = false,
                    pinned = false,
                    showPinToggle = true,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onMarkRead = {},
                    onMarkUnread = {},
                    onAddToFolder = {},
                    onArchiveToggle = {},
                    onMuteToggle = {},
                    onPinToggle = {},
                    onMovePinned = {},
                    onSelect = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
    }
}
