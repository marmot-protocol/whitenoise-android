package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListTeardownIntegrationTest {
    @Test
    fun accountTeardownAndCancelledBindCloseGroupSubscriptionExactlyOnce() {
        val diagnostics = testRecoveryDiagnostics()
        val subscription = ScriptedChatListSubscription(notificationChatListRow())
        val groups = ScriptedChatsSubscription()
        val appState = chatListTestAppState(diagnostics, subscription, groups)
        val controller = ChatsController(
            appState = appState,
            initialAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            memberSnapshotRetryDelay = { Long.MAX_VALUE },
            memberSnapshotLoader = { _, _ -> conversationTimelineMemberSnapshot().members },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val binding = scope.launch { controller.bind(ConversationTimelineTestIds.ACCOUNT_REF) }
        try {
            awaitChatListCondition { subscription.nextUpdateStarted.isCompleted }
            val teardown = scope.launch {
                controller.closeLiveSubscriptionsForAccountTeardown(ConversationTimelineTestIds.ACCOUNT_REF)
            }
            awaitChatListCondition { teardown.isCompleted && binding.isCompleted }
            assertEquals(1, groups.closeCalls)
        } finally {
            controller.onCleared()
            scope.cancel()
        }
    }

}
