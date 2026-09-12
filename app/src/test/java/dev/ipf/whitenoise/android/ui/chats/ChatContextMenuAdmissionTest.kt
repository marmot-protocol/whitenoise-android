package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.common.dispatchNativePopupBack
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/** A displayed command must recheck ownership at dispatch, independently of recomposition timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatContextMenuAdmissionTest {
    @get:Rule val composeRule = createComposeRule()
    private val current = AtomicBoolean(true)
    private val expanded = mutableStateOf(true)
    private var actions = 0
    private var dismissals = 0

    @Test fun staleDeleteDismissesWithoutDispatching() = rejectStaleCommand("Delete")

    @Test fun stalePinDismissesWithoutDispatching() = rejectStaleCommand("Pin")

    @Test fun backDismissesWithoutDispatching() {
        render()
        composeRule.onNode(isPopup()).dispatchNativePopupBack()
        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertEquals(0, actions)
        assertEquals(1, dismissals)
    }

    private fun rejectStaleCommand(tag: String) {
        render()
        // AtomicBoolean deliberately does not recompose away the popup before the queued click runs.
        current.set(false)
        composeRule.onNodeWithTag("chat.action.$tag").performClick()
        composeRule.onNode(isPopup()).assertDoesNotExist()
        assertEquals(0, actions)
        assertEquals(1, dismissals)
    }

    private fun render() {
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
                    onMarkRead = { actions++ },
                    onMarkUnread = { actions++ },
                    onAddToFolder = { actions++ },
                    onArchiveToggle = { actions++ },
                    onMuteToggle = { actions++ },
                    onPinToggle = { actions++ },
                    onMovePinned = { actions++ },
                    onSelect = { actions++ },
                    onDelete = { actions++ },
                    onDismiss = {
                        expanded.value = false
                        dismissals++
                    },
                    expanded = expanded.value,
                    canRunAction = current::get,
                )
            }
        }
    }
}
