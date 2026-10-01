package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.whitenoise.android.ui.conversation.observeConversationVisibleReads
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Drives the exact production Compose read observer without manual mark-read calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationVisibleReadObserverTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun resumedSettledAnchorRetriesWhenConversationAndForegroundOwnershipReturn() {
        val row = reminderRow()
        val fixture = fixture(row)
        runBlocking { fixture.bootstrap() }
        val state = fixture.appState
        val controller = controller(state, row)
        var observing by mutableStateOf(true)
        try {
            composeRule.runOnIdle {
                state.setAppInForeground(true, dismissRetainedVisibleConversation = false)
                state.clearActiveConversation()
            }
            val lifecycleOwner = ReadLifecycleOwner()
            assertEquals(Lifecycle.State.RESUMED, lifecycleOwner.lifecycle.currentStateFlow.value)
            composeRule.setContent {
                Text("Read observer")
                if (observing) {
                    observeConversationVisibleReads(state, controller, lifecycleOwner) {
                        ConversationTimelineTestIds.MESSAGE_B
                    }
                }
            }
            composeRule.waitForIdle()
            assertEquals(0, fixture.markReadCalls.get())
            composeRule.runOnIdle { state.setActiveConversationFromUi("another-account", row.groupIdHex) }
            composeRule.waitForIdle()
            assertEquals(0, fixture.markReadCalls.get())
            activate(state, row.groupIdHex)
            awaitReads(fixture, controller, 1)

            composeRule.runOnIdle { state.clearActiveConversation() }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                controller.applyAuthoritativeChatListRow(ConversationTimelineTestIds.ACCOUNT_REF, row)
                Snapshot.sendApplyNotifications()
            }
            composeRule.waitForIdle()
            assertEquals(1, fixture.markReadCalls.get())
            activate(state, row.groupIdHex)
            awaitReads(fixture, controller, 2)

            composeRule.runOnIdle {
                state.setAppInForeground(false)
                controller.applyAuthoritativeChatListRow(ConversationTimelineTestIds.ACCOUNT_REF, row)
                Snapshot.sendApplyNotifications()
            }
            composeRule.waitForIdle()
            assertEquals(2, fixture.markReadCalls.get())
            composeRule.runOnIdle { state.setAppInForeground(true, dismissRetainedVisibleConversation = false) }
            awaitReads(fixture, controller, 3)
            assertEquals(Lifecycle.State.RESUMED, lifecycleOwner.lifecycle.currentState)
        } finally {
            composeRule.runOnIdle { observing = false }
            composeRule.waitForIdle()
            controller.onCleared()
            fixture.close()
        }
    }

    private fun activate(
        state: WhiteNoiseAppState,
        groupIdHex: String,
    ) {
        composeRule.runOnIdle {
            state.setActiveConversationFromUi(ConversationTimelineTestIds.ACCOUNT_REF, groupIdHex)
            assertTrue(state.isConversationReadVisible(ConversationTimelineTestIds.ACCOUNT_REF, groupIdHex))
            Snapshot.sendApplyNotifications()
        }
    }

    private fun reminderRow() =
        notificationChatListRow().copy(
            lastReadMessageIdHex = ConversationTimelineTestIds.MESSAGE_B,
            manuallyMarkedUnread = true,
            hasUnread = true,
            unreadCount = 0uL,
        )

    private fun fixture(row: ChatListRowFfi) =
        NotificationBootstrapTestFixture(
            context = ApplicationProvider.getApplicationContext(),
            accounts = listOf(account()),
            chatListRows = listOf(row),
            chatGroups = listOf(conversationTimelineTestGroup()),
            emitStartupNotification = false,
            onMarkTimelineMessageRead = { row.copy(manuallyMarkedUnread = false, hasUnread = false) },
        )

    private fun controller(
        state: WhiteNoiseAppState,
        row: ChatListRowFfi,
    ) = ConversationController(
        appState = state,
        initialGroup = conversationTimelineTestGroup(),
        initialMemberSnapshot = conversationTimelineMemberSnapshot(),
        initialChatListRow = row,
        accountRefOverride = ConversationTimelineTestIds.ACCOUNT_REF,
    )

    private fun awaitReads(
        fixture: NotificationBootstrapTestFixture,
        controller: ConversationController,
        count: Int,
    ) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            // The native bootstrap fixture posts platform work to Robolectric's
            // paused Android looper, separate from Compose's test clock.
            shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            fixture.markReadCalls.get() >= count && controller.latestChatListRow?.manuallyMarkedUnread == false
        }
        composeRule.waitForIdle()
        assertEquals(count, fixture.markReadCalls.get())
    }

    private fun account() =
        AccountSummaryFfi(
            label = ConversationTimelineTestIds.ACCOUNT_REF,
            accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private class ReadLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
}
