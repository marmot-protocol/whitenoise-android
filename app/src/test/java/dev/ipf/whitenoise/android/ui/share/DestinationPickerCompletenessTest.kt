package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.presentedRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Completeness belongs to the existing account read, independently of the retained local window. */
@RunWith(RobolectricTestRunner::class)
class DestinationPickerCompletenessTest {
    @get:Rule val composeRule = createComposeRule()

    /** A failed retry keeps previously chosen off-window destinations without claiming a complete folder. */
    @Test
    fun failedRetryRetainsSameAccountTargetsAndReportsIncomplete() {
        val state = emptyAppState()
        var failRead = false
        var reads = 0
        state.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("No live window in this fixture") },
                openChats = { _, _ -> error("No live feed in this fixture") },
                presentedChatList = { _, _ ->
                    reads += 1
                    check(!failRead)
                    listOf(presentedRow(GROUP_B))
                },
            )
        val controller = ChatsController(state, ACCOUNT_REF) { _, _ -> emptyList() }
        controller.applyLocalDirectChat(GROUP_A, ACCOUNT_HEX, PEER_A)
        state.attachChatsController(controller)
        var source: ShareChatPickerDataSource? = null
        composeRule.setContent {
            val current = rememberShareChatPickerDataSource(state, ACCOUNT_REF, { error("active owner") }, { _, _ -> })
            SideEffect { source = current }
        }
        composeRule.waitUntil(5_000) { source?.targetsComplete == true }
        composeRule.runOnIdle {
            assertTrue(source!!.targets.any { it.group.groupIdHex == GROUP_B })
            failRead = true
            source!!.retryLoad()
        }
        composeRule.waitUntil(5_000) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            reads >= 2 && source?.targetsComplete == false
        }
        composeRule.runOnIdle {
            assertFalse(source!!.targetsComplete)
            assertTrue(source!!.targets.any { it.group.groupIdHex == GROUP_B })
        }
    }
}
