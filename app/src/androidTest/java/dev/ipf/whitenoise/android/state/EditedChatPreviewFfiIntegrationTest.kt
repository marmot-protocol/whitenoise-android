package dev.ipf.whitenoise.android.state

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.ChatListSubscription
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessagesSubscription
import dev.ipf.marmotkit.TimelineSubscriptionUpdateFfi
import dev.ipf.whitenoise.android.core.MarmotClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real packaged-MDK edit projections through both subscriptions and the hidden Android list. */
@RunWith(AndroidJUnit4::class)
class EditedChatPreviewFfiIntegrationTest {
    /** Disposable identities exercise native accepted edits without accessing the user's app database. */
    @Test
    @Suppress("LongMethod") // Native setup, dual subscription, first-frame assertions and cleanup form one scenario.
    fun acceptedEditReachesHiddenListAndReconnectSnapshot() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "edit-preview-ffi-${UUID.randomUUID()}").apply { mkdirs() }
            val native = Marmot(root.absolutePath, MarmotClient.bootstrapRelays)
            var chats: ChatsController? = null
            var nativeClosed = false
            var phase = "start"
            try {
                withTimeout(120_000) {
                    native.start()
                    phase = "create identity"
                    android.util.Log.i("EditPreviewFixture", phase)
                    val account = native.createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)
                    phase = "create group"
                    android.util.Log.i("EditPreviewFixture", phase)
                    val group = native.createGroup(account.label, "Edit preview fixture", emptyList(), null)
                    phase = "send original"
                    android.util.Log.i("EditPreviewFixture", phase)
                    native.sendText(account.label, group, "Before edit")
                    val before = native.chatList(account.label, true).single { it.groupIdHex == group }
                    val target = requireNotNull(before.lastMessage).messageIdHex
                    val state =
                        WhiteNoiseAppState(
                            context,
                            DraftStore.forContext(context),
                            { account.accountIdHex },
                            listOf(account),
                            account.label,
                        )
                    val controller =
                        withContext(Dispatchers.Main.immediate) {
                            ChatsController(
                                state,
                                initialAccountRef = account.label,
                                memberSnapshotLoader = { _, _ -> emptyList() },
                            ).also {
                                chats = it
                                it.setChatListVisible(false)
                                it.applyChatListRow(before)
                            }
                        }
                    phase = "subscribe"
                    android.util.Log.i("EditPreviewFixture", phase)
                    native.subscribeChatList(account.label, true).use { list ->
                        native.subscribeTimelineMessages(account.label, group, 100u).use { timeline ->
                            val listResult = async { awaitEditedRow(list, controller, account.label, target) }
                            val timelineResult = async { awaitEditedTimeline(timeline, target) }
                            phase = "edit publication"
                            android.util.Log.i("EditPreviewFixture", phase)
                            native.editMessage(account.label, group, target, EDITED)
                            phase = "edited chat-list projection"
                            android.util.Log.i("EditPreviewFixture", phase)
                            val row = listResult.await()
                            phase = "edited timeline projection"
                            android.util.Log.i("EditPreviewFixture", phase)
                            val message = timelineResult.await()
                            assertNotNull(message.edit)
                            assertEquals(message.contentTokens.blocks, row.lastMessage?.contentTokens?.blocks)
                            assertEquals(before.activitySortAt, row.activitySortAt)
                            assertEquals(before.unreadCount, row.unreadCount)
                            assertEquals(before.unreadMentionCount, row.unreadMentionCount)
                            assertEquals(before.lastMessage?.timelineAt, row.lastMessage?.timelineAt)
                            assertEquals(before.lastMessage?.deliveryState, row.lastMessage?.deliveryState)
                            withContext(Dispatchers.Main.immediate) {
                                controller.setChatListVisible(true)
                                assertEquals(
                                    EDITED,
                                    controller.items
                                        .single()
                                        .projection
                                        ?.lastMessage
                                        ?.plaintext,
                                )
                                assertEquals(
                                    row.lastMessage?.contentTokens?.blocks,
                                    controller.items
                                        .single()
                                        .projection
                                        ?.lastMessage
                                        ?.contentTokens
                                        ?.blocks,
                                )
                            }
                        }
                    }
                    // Reconnect reads the subscription snapshot before consuming subsequent update events.
                    phase = "reconnect snapshot"
                    android.util.Log.i("EditPreviewFixture", phase)
                    native.subscribeChatList(account.label, true).use { replacement ->
                        val restored = replacement.snapshot().single { it.groupIdHex == group }
                        assertEquals(EDITED, restored.lastMessage?.plaintext)
                    }
                    assertEquals(
                        EDITED,
                        native
                            .chatList(account.label, true)
                            .single { it.groupIdHex == group }
                            .lastMessage
                            ?.plaintext,
                    )
                    phase = "cold snapshot"
                    native.shutdownAndClose()
                    nativeClosed = true
                    Marmot(root.absolutePath, emptyList()).use { reopened ->
                        val cold = reopened.chatList(account.label, true).single { it.groupIdHex == group }
                        assertEquals(EDITED, cold.lastMessage?.plaintext)
                        assertEquals(before.activitySortAt, cold.activitySortAt)
                    }
                }
            } catch (failure: Throwable) {
                throw AssertionError("Native edit fixture failed during $phase", failure)
            } finally {
                withContext(Dispatchers.Main.immediate) { chats?.onCleared() }
                try {
                    if (!nativeClosed) withTimeout(15_000) { native.shutdownAndClose() }
                } finally {
                    native.close()
                    root.deleteRecursively()
                }
            }
        }

    /** Fold real ordered updates while hidden, including the subscription's recovery snapshot. */
    private suspend fun awaitEditedRow(
        subscription: ChatListSubscription,
        controller: ChatsController,
        account: String,
        target: String,
    ): dev.ipf.marmotkit.ChatListRowFfi {
        while (true) {
            val update = requireNotNull(subscription.nextUpdate())
            withContext(Dispatchers.Main.immediate) { controller.applyChatListSubscriptionUpdate(account, update) }
            val rows =
                when (update) {
                    is ChatListSubscriptionUpdateFfi.Row -> listOf(update.row)
                    is ChatListSubscriptionUpdateFfi.Snapshot -> update.rows
                    is ChatListSubscriptionUpdateFfi.RemoveRow -> emptyList()
                }
            rows
                .firstOrNull {
                    it.lastMessage?.messageIdHex == target && it.lastMessage?.plaintext == EDITED
                }?.let {
                    if (update is ChatListSubscriptionUpdateFfi.Row) {
                        assertEquals(ChatListUpdateTriggerFfi.LAST_MESSAGE_CONTENT_CHANGED, update.trigger)
                    }
                    return it
                }
        }
    }

    /** The timeline must independently identify an accepted edit of the original message. */
    private suspend fun awaitEditedTimeline(
        subscription: TimelineMessagesSubscription,
        target: String,
    ): TimelineMessageRecordFfi {
        while (true) {
            val update = requireNotNull(subscription.nextUpdate())
            val messages =
                when (update) {
                    is TimelineSubscriptionUpdateFfi.Page -> update.page.messages
                    is TimelineSubscriptionUpdateFfi.Projection ->
                        update.update.update.let { projection ->
                            projection.messages +
                                projection.changes.mapNotNull {
                                    (it as? TimelineMessageChangeFfi.Upsert)?.message
                                }
                        }
                }
            messages
                .firstOrNull {
                    it.messageIdHex == target && it.plaintext == EDITED && it.edit != null
                }?.let { return it }
        }
    }

    private companion object {
        const val EDITED = "**Edited** native preview"
    }
}
