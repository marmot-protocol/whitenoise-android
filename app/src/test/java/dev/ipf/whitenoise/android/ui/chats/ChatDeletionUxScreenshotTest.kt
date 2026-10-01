package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.ChatListRowActionsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Local history deletion and group departure have distinct labels, confirmations and callbacks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatDeletionUxScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A permitted departure never dispatches the local-only callback. */
    @Test fun departureRoutesOnlyItsOwnCallback() {
        var localDeletes = 0
        var departures = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                menu(true, { localDeletes++ }, { departures++ })
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.delete_from_device)).assertIsDisplayed()
        composeRule.onNodeWithTag("chat.action.LeaveAndDelete").performClick()
        assertEquals(0, localDeletes)
        assertEquals(1, departures)
    }

    /** The native row capability is required even when a callback exists. */
    @Test fun departureIsAbsentWhenNativeCapabilityIsWithheld() {
        composeRule.setContent { WhiteNoiseTheme { menu(false, {}, {}) } }
        composeRule.onNodeWithTag("chat.action.LeaveAndDelete").assertDoesNotExist()
        composeRule.onNodeWithTag("chat.action.Delete").assertIsDisplayed()
    }

    /** Cancellation is side-effect-free; only the explicit confirm submits departure. */
    @Test fun departureConfirmationHasNoImplicitSideEffect() {
        var confirmations = 0
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatLeaveAndDeleteConfirmationDialog({ confirmations++ }, { dismissals++ })
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.leave_and_delete_message)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertEquals(0, confirmations)
        assertEquals(1, dismissals)
        composeRule.onNode(hasText(context.getString(R.string.leave_and_delete)) and hasClickAction()).performClick()
        assertEquals(1, confirmations)
    }

    /** The single-group menu makes both effects visible without explanatory paragraphs. */
    @Test fun groupMenuLightScreenshot() {
        composeRule.setContent { WhiteNoiseTheme { menu(true, {}, {}) } }
        composeRule.onNode(isPopup()).captureRoboImage("src/test/snapshots/chat_delete_choices_light.png")
    }

    /** Local-only confirmation is explicit in the default theme. */
    @Test fun localDeleteLightScreenshot() = captureDialog("chat_delete_local_light", dark = false, leave = false)

    /** Departure is visually separate in dark mode. */
    @Test fun leaveAndDeleteDarkScreenshot() = captureDialog("chat_leave_delete_dark", dark = true, leave = true)

    /** Long labels remain readable with large text and RTL. */
    @Test
    @Config(sdk = [36], qualifiers = "ar-w360dp-h780dp-mdpi")
    fun localDeleteRtlLargeScreenshot() = captureDialog("chat_delete_local_rtl_large", dark = false, leave = false, scale = 2f)

    /** Records the actual shared confirmation surface. */
    private fun captureDialog(name: String, dark: Boolean, leave: Boolean, scale: Float = 1f) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = scale) {
                if (leave) ChatLeaveAndDeleteConfirmationDialog({}, {}) else ChatDeleteConfirmationDialog(1, {}, {})
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Supplies the native advisory capability rather than synthesizing permission. */
    @Composable
    private fun menu(canLeave: Boolean, onLocal: () -> Unit, onLeave: () -> Unit) {
        ChatContextMenu(
            hasUnread = false, canMarkUnread = true, archived = false, muted = false, pinned = false,
            showPinToggle = false, showMovePinnedUp = false, showMovePinnedDown = false,
            onMarkRead = {}, onMarkUnread = {}, onAddToFolder = {}, onArchiveToggle = {}, onMuteToggle = {},
            onPinToggle = {}, onMovePinned = {}, onSelect = {}, onDelete = onLocal, onDismiss = {},
            actions = ChatListRowActionsFfi(false, false, false, false, false, false, false, false, canLeave, true),
            onLeaveAndDelete = onLeave,
        )
    }
}
