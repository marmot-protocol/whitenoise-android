package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.captureClickCallbackForReplay
import dev.ipf.whitenoise.android.ui.common.dispatchNativePopupBack
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real bottom-bar callbacks must consult the live selected-account scope after admission or leaving. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatSelectionAdmissionTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun closeThenCapturedSelectAllInSameFrameCannotRestoreSelection() {
        var current = true
        var selections = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    ChatListSelectionBar { current = false }
                    chatSelectionFixture(isCurrent = { current }, onSelectAll = { selections++ })
                }
            }
        }
        val select =
            composeRule
                .onNodeWithText(context.getString(R.string.chat_list_select_all))
                .captureClickCallbackForReplay()
        val close =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.close))
                .captureClickCallbackForReplay()
        composeRule.runOnIdle {
            close()
            select()
        }
        assertEquals(0, selections)
    }

    @Test fun capturedNativeArchiveActionCannotRunForReplacementSelection() {
        var current = true
        var archives = 0
        composeRule.setContent {
            WhiteNoiseTheme { chatSelectionFixture(isCurrent = { current }, onArchive = { archives++ }) }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.actions)).performClick()
        val archive =
            composeRule
                .onNodeWithText(context.getString(R.string.archive))
                .captureClickCallbackForReplay()
        composeRule.runOnIdle {
            current = false
            archive()
        }
        assertEquals(0, archives)
    }

    @Test fun dismissedPopupRejectsCapturedArchiveAndDeleteAfterReopen() {
        var actions = 0
        composeRule.setContent {
            WhiteNoiseTheme { chatSelectionFixture(onArchive = { actions++ }, onDelete = { actions++ }) }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.actions)).performClick()
        val archive =
            composeRule
                .onNodeWithText(context.getString(R.string.archive))
                .captureClickCallbackForReplay()
        val delete =
            composeRule
                .onNodeWithText(context.getString(R.string.delete))
                .captureClickCallbackForReplay()
        composeRule.onNode(isPopup()).dispatchNativePopupBack()
        composeRule.runOnIdle {
            archive()
            delete()
        }
        assertEquals(0, actions)
        composeRule.onNodeWithContentDescription(context.getString(R.string.actions)).performClick()
        composeRule.runOnIdle {
            archive()
            delete()
        }
        assertEquals(0, actions)
        composeRule.onNodeWithText(context.getString(R.string.delete)).performClick()
        assertEquals(1, actions)
    }

    @Test fun disposedBottomBarRevokesItsCapturedAction() {
        val visible = mutableStateOf(true)
        var selections = 0
        composeRule.setContent {
            WhiteNoiseTheme { if (visible.value) chatSelectionFixture(onSelectAll = { selections++ }) }
        }
        val select =
            composeRule
                .onNodeWithText(context.getString(R.string.chat_list_select_all))
                .captureClickCallbackForReplay()
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { select() }
        assertEquals(0, selections)
    }
}

/**
 * Presentation fixture routes the exact production controls, with explicit eligibility rather than fake
 * native callbacks.
 */
@Suppress("LongParameterList")
@Composable
internal fun chatSelectionFixture(
    count: Int = 2,
    all: Boolean = false,
    single: Boolean = false,
    isCurrent: () -> Boolean = { true },
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSelectAll: () -> Unit = {},
) {
    ChatListSelectionControls(
        count = count,
        archiveAction = ChatListBulkArchiveAction.Archive,
        actionsEnabled = count > 0,
        allVisibleSelected = all,
        showMarkRead = single,
        showMarkUnread = false,
        showMuteToggle = single,
        muted = false,
        showPinToggle = single,
        pinned = false,
        showMovePinnedUp = false,
        showMovePinnedDown = false,
        onArchive = onArchive,
        onDelete = onDelete,
        onAddToFolder = {},
        onMarkRead = {},
        onMarkUnread = {},
        onMuteToggle = {},
        onPinToggle = {},
        onMovePinned = {},
        onSelectAll = onSelectAll,
        onDeselectAll = {},
        isCurrent = isCurrent,
    )
}
