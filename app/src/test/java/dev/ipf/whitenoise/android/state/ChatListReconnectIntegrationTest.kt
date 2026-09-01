package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** End-to-end JVM coverage for recovery attribution through the chat-list projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListReconnectIntegrationTest {
    /** A recovered subscription row reaches the authoritative list under one generation. */
    @Test
    fun recoveryGenerationReachesTheAuthoritativeChatListRow() =
        runBlocking {
            val diagnostics = testRecoveryDiagnostics()
            val initialRow = notificationChatListRow().copy(lastMessage = null, unreadCount = 0uL, hasUnread = false)
            val subscription = ScriptedChatListSubscription(initialRow)
            val groupSubscription = ScriptedChatsSubscription()
            val appState = chatListTestAppState(diagnostics, subscription, groupSubscription)
            val controller =
                ChatsController(
                    appState = appState,
                    initialAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
                    memberSnapshotRetryDelay = { Long.MAX_VALUE },
                    memberSnapshotLoader = { _, _ -> conversationTimelineMemberSnapshot().members },
                )
            val bindJob = launch(Dispatchers.Main) { controller.bind(ConversationTimelineTestIds.ACCOUNT_REF) }
            try {
                awaitConversationCondition { subscription.nextUpdateStarted.isCompleted }
                diagnostics.networkRestored(7L)
                diagnostics.attemptStarted(7L, 1)
                diagnostics.catchUpSucceeded(7L, 1)
                subscription.emit(
                    ChatListSubscriptionUpdateFfi.Row(
                        row = notificationChatListRow(),
                        trigger = ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
                    ),
                )

                awaitConversationCondition {
                    controller.items
                        .singleOrNull()
                        ?.lastMessage
                        ?.messageIdHex == ConversationTimelineTestIds.MESSAGE_B &&
                        controller.recoveryProjectionGeneration == 7L
                }
                val phases = diagnostics.samples().filter { it.generation == 7L }.map { it.phase }
                assertEquals(1uL, controller.items.single().unreadCount)
                assertTrue(
                    phases.indexOf(PerformancePhase.CURRENT_REPLAY_COMPLETE) <
                        phases.indexOf(PerformancePhase.CHAT_LIST_SUBSCRIPTION_RECEIVED),
                )
                assertTrue(
                    phases.indexOf(PerformancePhase.CHAT_LIST_SUBSCRIPTION_RECEIVED) <
                        phases.indexOf(PerformancePhase.CHAT_LIST_PROJECTION_PUBLISHED),
                )
            } finally {
                controller.onCleared()
                bindJob.cancel()
                subscription.close()
                groupSubscription.close()
            }
        }

    /** Builds a numeric-only diagnostics collector without enabling logcat output. */
    private fun testRecoveryDiagnostics(): NotificationNetworkRecoveryDiagnostics =
        NotificationNetworkRecoveryDiagnostics(
            traceFactory = { null },
            traceRecorder = { _, _, _, _, _, _, _ -> },
        )

    /** Builds an app state whose live chat-list sources are fully controlled by this test. */
    private fun chatListTestAppState(
        diagnostics: NotificationNetworkRecoveryDiagnostics,
        chatList: ChatListSubscriptionHandle,
        chats: ChatsSubscriptionHandle,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ConversationTimelineTestIds.ACCOUNT_REF,
                        accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            notificationNetworkRecoveryDiagnostics = diagnostics,
        ).also { state ->
            state.liveSubscriptionOverrides.chatList =
                ChatListLiveSubscriptions(
                    openChatList = { _, _ -> chatList },
                    openChats = { _, _ -> chats },
                )
        }
}

/** Controllable chat-list subscription used by the recovery integration test. */
private class ScriptedChatListSubscription(
    private val initialRow: ChatListRowFfi,
) : ChatListSubscriptionHandle {
    private val updates = Channel<ChatListSubscriptionUpdateFfi>(Channel.UNLIMITED)
    val nextUpdateStarted = CompletableDeferred<Unit>()

    /** Returns the local projection present before reconnect. */
    override fun snapshot(): List<ChatListRowFfi> = listOf(initialRow)

    /** Waits for the test-controlled authoritative recovery update. */
    override suspend fun nextUpdate(): ChatListSubscriptionUpdateFfi? {
        nextUpdateStarted.complete(Unit)
        return updates.receiveCatching().getOrNull()
    }

    /** Delivers one authoritative update without blocking the test thread. */
    fun emit(update: ChatListSubscriptionUpdateFfi) {
        check(updates.trySend(update).isSuccess)
    }

    /** Ends the scripted stream. */
    override fun close() {
        updates.close()
    }
}

/** Stable companion group subscription that lives until the controller closes it. */
private class ScriptedChatsSubscription : ChatsSubscriptionHandle {
    private val closed = CompletableDeferred<Unit>()

    /** No group-state row is needed for the named chat-list fixture. */
    override fun snapshot(): List<AppGroupRecordFfi> = emptyList()

    /** Holds the paired stream open until teardown. */
    override suspend fun next(): AppGroupRecordFfi? {
        closed.await()
        return null
    }

    /** Releases the paired stream. */
    override fun close() {
        closed.complete(Unit)
    }
}
