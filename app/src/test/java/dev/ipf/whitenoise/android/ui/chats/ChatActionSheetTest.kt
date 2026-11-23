package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

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
        composeRule.onNodeWithText(string(R.string.delete_from_device)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.select)).performClick()

        assertEquals(1, dismisses)
        assertEquals(1, selects)
    }

    /** The envelope icons leave the read-state callbacks as they were: each command fires its own once. */
    @Test
    fun readStateCommandsKeepTheirCallbacks() {
        var hasUnread by mutableStateOf(true)
        var reads = 0
        var unreads = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatContextMenu(
                    hasUnread = hasUnread,
                    canMarkUnread = true,
                    archived = false,
                    muted = false,
                    pinned = false,
                    showPinToggle = false,
                    showMovePinnedUp = false,
                    showMovePinnedDown = false,
                    onMarkRead = { reads++ },
                    onMarkUnread = { unreads++ },
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

        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_read)).performClick()
        hasUnread = false
        composeRule.onNodeWithText(string(R.string.chat_row_action_mark_unread)).performClick()

        assertEquals(1, reads)
        assertEquals(1, unreads)
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
            .onNodeWithText(string(R.string.delete_from_device))
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

    /** Mark read shows the open envelope beside Select's checkmark in the light theme (#3566). */
    @Test
    fun actionSheetMarkReadLightScreenshot() {
        renderScreenshotSheet(darkTheme = false, hasUnread = true)
        captureSheet("chat_action_sheet_mark_read_light")
    }

    /** Mark read and Select stay distinct in the dark theme. */
    @Test
    fun actionSheetMarkReadDarkScreenshot() {
        renderScreenshotSheet(darkTheme = true, hasUnread = true)
        captureSheet("chat_action_sheet_mark_read_dark")
    }

    /** Mark read and Select stay distinct in the AMOLED theme. */
    @Test
    fun actionSheetMarkReadAmoledScreenshot() {
        renderScreenshotSheet(darkTheme = true, hasUnread = true, amoled = true)
        captureSheet("chat_action_sheet_mark_read_amoled")
    }

    /** The envelope outline stays legible at double font scale in RTL. */
    @Test
    fun actionSheetMarkReadRtlLargeScreenshot() {
        renderScreenshotSheet(darkTheme = false, hasUnread = true, rtl = true, fontScale = 2f)
        captureSheet("chat_action_sheet_mark_read_rtl_large")
    }

    /** Captures the sheet's popup as [name]. */
    private fun captureSheet(name: String) {
        composeRule.onNode(isPopup(), useUnmergedTree = true).captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Composes the sheet for a screenshot capture, offering Mark read when [hasUnread]. */
    private fun renderScreenshotSheet(
        darkTheme: Boolean,
        hasUnread: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = fontScale) {
                    SheetFixture(hasUnread)
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** The production sheet with read-state and Select commands, no callbacks attached. */
    @Suppress("FunctionNaming")
    @Composable
    private fun SheetFixture(hasUnread: Boolean) {
        ChatContextMenu(
            hasUnread = hasUnread,
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

    @Test fun capturePopupFrames() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val shown = mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    ChatContextMenu(
                        expanded = shown.value,
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
                        onDismiss = { shown.value = false },
                    )
                }
            }
        }
        captureTransitionFrames("popup/forward") { composeRule.runOnIdle { shown.value = true } }
        captureTransitionFrames("popup/reverse") { composeRule.onNodeWithText(string(R.string.select)).performClick() }
    }

    private fun captureTransitionFrames(
        name: String,
        action: () -> Unit,
    ) {
        check(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        composeRule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(32) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }
}
