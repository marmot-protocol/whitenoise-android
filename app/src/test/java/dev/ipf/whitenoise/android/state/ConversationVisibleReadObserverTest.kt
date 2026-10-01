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

    /**
     * Exercises the production observer: ownership reentry retries three reads while reporting one unchanged
     * anchor.
     */
    @Test
    fun resumedSettledAnchorRetriesWhenConversationAndForegroundOwnershipReturn() {
        val row = reminderRow()
        val fixture = fixture(row)
        runBlocking { fixture.bootstrap() }
        val state = fixture.appState
        val subscription = installTimeline(state)
        val controller = controller(state, row)
        awaitTimeline(controller, subscription)
        var observing by mutableStateOf(true)
        try {
            composeRule.runOnIdle {
                state.setAppInForeground(true, dismissRetainedVisibleConversation = false)
                state.clearActiveConversation()
            }
            val lifecycleOwner = ReadLifecycleOwner()
            assertEquals(Lifecycle.State.RESUMED, lifecycleOwner.lifecycle.currentStateFlow.value)
            installObserver(state, controller, lifecycleOwner) { observing }
            composeRule.waitForIdle()
            assertEquals(0, fixture.markReadCalls.get())
            composeRule.runOnIdle { state.setActiveConversationFromUi("another-account", row.groupIdHex) }
            composeRule.waitForIdle()
            assertEquals(0, fixture.markReadCalls.get())
            activate(state, row.groupIdHex)
            awaitReads(fixture, controller, 1)
            awaitAnchor(subscription)
            assertEquals(listOf(ConversationTimelineTestIds.MESSAGE_B), subscription.anchorReports)

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
            assertEquals(listOf(ConversationTimelineTestIds.MESSAGE_B), subscription.anchorReports)
            assertEquals(Lifecycle.State.RESUMED, lifecycleOwner.lifecycle.currentState)
        } finally {
            composeRule.runOnIdle { observing = false }
            composeRule.waitForIdle()
            controller.onCleared()
            fixture.close()
        }
    }

    /** Mounts the production composable with a stable anchor and controllable presence in composition. */
    private fun installObserver(
        state: WhiteNoiseAppState,
        controller: ConversationController,
        lifecycleOwner: LifecycleOwner,
        observing: () -> Boolean,
    ) {
        composeRule.setContent {
            Text("Read observer")
            if (observing()) {
                observeConversationVisibleReads(state, controller, lifecycleOwner) {
                    ConversationTimelineTestIds.MESSAGE_B
                }
            }
        }
    }

    /** Supplies a real controller subscription path with one retained native timeline record. */
    private fun installTimeline(state: WhiteNoiseAppState): ScriptedConversationTimelineSubscription {
        val subscription =
            ScriptedConversationTimelineSubscription(
                timelinePage(timelineRecord(ConversationTimelineTestIds.MESSAGE_B, timelineAt = 2uL)),
            )
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(subscription),
                group = conversationTimelineTestGroup(),
            )
        state.liveSubscriptionOverrides.conversation = scripted.subscriptions
        return subscription
    }

    /** Waits until the controller owns the scripted subscription and retains its visible record. */
    private fun awaitTimeline(
        controller: ConversationController,
        subscription: ScriptedConversationTimelineSubscription,
    ) = runBlocking {
        awaitConversationCondition {
            controller.timelineSubscription === subscription &&
                controller.retainsTimelineRecord(ConversationTimelineTestIds.MESSAGE_B)
        }
    }

    /** Pumps Android work until the production observer reports its first native visible anchor. */
    private fun awaitAnchor(subscription: ScriptedConversationTimelineSubscription) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            subscription.anchorReports.isNotEmpty()
        }
    }

    /** Transfers visible ownership on the Compose thread and publishes its snapshot transition. */
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

    /** Builds manual attention at the retained message without any actual unread messages. */
    private fun reminderRow() =
        notificationChatListRow().copy(
            lastReadMessageIdHex = ConversationTimelineTestIds.MESSAGE_B,
            manuallyMarkedUnread = true,
            hasUnread = true,
            unreadCount = 0uL,
        )

    /** Provides a native read result that clears attention while preserving the saved cursor. */
    private fun fixture(row: ChatListRowFfi) =
        NotificationBootstrapTestFixture(
            context = ApplicationProvider.getApplicationContext(),
            accounts = listOf(account()),
            chatListRows = listOf(row),
            chatGroups = listOf(conversationTimelineTestGroup()),
            emitStartupNotification = false,
            onMarkTimelineMessageRead = { row.copy(manuallyMarkedUnread = false, hasUnread = false) },
        )

    /** Starts a controller bound to the fixture account so the observer uses a real opened timeline. */
    private fun controller(
        state: WhiteNoiseAppState,
        row: ChatListRowFfi,
    ) = ConversationController(
        appState = state,
        initialGroup = conversationTimelineTestGroup(),
        initialMemberSnapshot = conversationTimelineMemberSnapshot(),
        initialChatListRow = row,
        accountRefOverride = ConversationTimelineTestIds.ACCOUNT_REF,
        groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
        startOnConstruction = true,
    )

    /** Pumps Android and Compose until the expected native read count and cleared reminder are observed. */
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

    /** Builds the running local account used by both the native fixture and controller ownership checks. */
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
