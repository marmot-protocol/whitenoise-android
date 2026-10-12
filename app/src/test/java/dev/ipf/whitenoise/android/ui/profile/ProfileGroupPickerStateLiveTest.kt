package dev.ipf.whitenoise.android.ui.profile

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ProfileAccountWideGroups
import dev.ipf.whitenoise.android.state.ProfileGroupPickerState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.PEER_A
import dev.ipf.whitenoise.android.ui.share.PEER_B
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.group
import dev.ipf.whitenoise.android.ui.share.member
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** A profile group picker follows group changes while the chat list is hidden behind a conversation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileGroupPickerStateLiveTest {
    @get:Rule val composeRule = createComposeRule()

    /** Losing admin rights drops a group from Add to a group although the visible chat list stays frozen. */
    @Test
    fun hiddenAdminChangeRefreshesTheAddToGroupsPicker() {
        val appState = emptyAppState()
        val controller = ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
        val adminGroup = group(GROUP).copy(name = "Group", admins = listOf(ACCOUNT_HEX))
        appState.attachChatsController(controller)
        controller.setChatListVisible(false)
        controller.applyChatListRow(chatRow(GROUP))
        val roster =
            listOf(member(ACCOUNT_HEX, local = true), member(PEER_B, local = false), member(THIRD, local = false))
        controller.applyLocalGroupDetails(adminGroup, roster)
        lateinit var state: ProfileGroupPickerState
        composeRule.setContent {
            state =
                rememberProfileGroupPickerState(
                    appState,
                    PEER_A,
                    ProfileAccountWideGroups(),
                    WhiteNoiseAppState::profileAddableGroupsState,
                )
        }
        composeRule.runOnIdle { assertEquals(listOf(GROUP), state.groups.map { it.group.groupIdHex }) }
        val frozen = controller.items

        composeRule.runOnIdle { controller.applyLocalGroupUpdate(adminGroup.copy(admins = emptyList())) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            // Compose's frame clock does not advance the controller's debounced recompute on the main looper.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            state.groups.isEmpty()
        }
        composeRule.runOnIdle { assertEquals(frozen, controller.items) }
    }

    private companion object {
        val GROUP = "f1".repeat(32)
        val THIRD = "f2".repeat(32)
    }
}
