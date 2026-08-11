package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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

    @Test
    fun omitsUnreadActionWhenMembershipCannotPersistIt() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatActionSheet(
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

    @Test
    fun rendersPinnedActionsAndRoutesMoveAfterDismissing() {
        var dismisses = 0
        var moveDelta = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatActionSheet(
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
        composeRule.onNodeWithText(string(R.string.chat_row_action_move_up)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_row_action_move_down)).performClick()

        assertEquals(1, dismisses)
        assertEquals(1, moveDelta)
    }

    @Test
    fun routesPinAfterDismissing() {
        var dismisses = 0
        var pins = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatActionSheet(
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

    @Test
    fun compactLargeTextSheetCanScrollToTheDestructiveAction() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    WhiteNoiseTheme {
                        ChatActionSheet(
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

    @Test
    fun actionSheetLightScreenshot() {
        renderScreenshotSheet(darkTheme = false)
        composeRule.onRoot(useUnmergedTree = true).captureRoboImage("src/test/snapshots/chat_action_sheet_light.png")
    }

    @Test
    fun actionSheetDarkScreenshot() {
        renderScreenshotSheet(darkTheme = true)
        composeRule.onRoot(useUnmergedTree = true).captureRoboImage("src/test/snapshots/chat_action_sheet_dark.png")
    }

    private fun renderScreenshotSheet(darkTheme: Boolean) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme) {
                ChatActionSheet(
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
