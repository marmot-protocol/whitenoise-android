package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationVisibleReadIntegrationTest {
    private val mountedChats = mutableListOf<ChatsController>()
    private val mountedConversations = mutableListOf<ConversationController>()

    @Test
    fun firstVisibleReadClearsManualAttentionAtSavedWatermarkBeforeReturningToList() =
        runBlocking {
            val reminder = reminderRow()
            val read = reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
            val fixture = fixture(reminder) { read }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, reminder)
                chats.setChatListVisible(false)
                val controller = controller(fixture.appState, reminder)
                assertEquals(MESSAGE_ID, controller.lastReadMessageId)

                controller.markReadUpTo(MESSAGE_ID)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                assertEquals(
                    listOf(Triple(ConversationTimelineTestIds.ACCOUNT_REF, reminder.groupIdHex, MESSAGE_ID)),
                    fixture.markReadRequests,
                )

                chats.setChatListVisible(true)
                val returned = chats.items.single().projection!!
                assertFalse(returned.manuallyMarkedUnread)
                assertFalse(returned.hasUnread)
                assertEquals(0uL, returned.unreadCount)
                assertEquals(reminder.lastReadMessageIdHex, returned.lastReadMessageIdHex)
                assertEquals(reminder.lastReadTimelineAt, returned.lastReadTimelineAt)

                // Rapid re-entry can retain this controller through the outgoing
                // animation. A new native reminder must bypass its old dedupe.
                chats.applyChatListRow(reminder)
                controller.markReadUpTo(MESSAGE_ID)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())

                // A later visit must perform its own visible read even if MDK
                // seeds the controller with the same durable watermark again.
                controller(fixture.appState, reminder).markReadUpTo(MESSAGE_ID)
                assertEquals(3, fixture.markReadCalls.get())
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun olderSameMessageReadFailureCannotUndoNewerSuccessfulRead() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val reminder = reminderRow()
            val fixture =
                fixture(reminder) {
                    if (calls.incrementAndGet() == 1) {
                        started.complete(Unit)
                        check(release.await(15, TimeUnit.SECONDS))
                        error("older read rejected")
                    }
                    reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
                }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, reminder)
                val controller = controller(fixture.appState, reminder)
                val older = async { controller.markReadUpTo(MESSAGE_ID) }
                started.await()
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())
                assertFalse("older request must still be in flight", older.isCompleted)
                assertFalse(chats.chatItemForGroup(reminder.groupIdHex)!!.projection!!.manuallyMarkedUnread)
                release.countDown()
                older.await()
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())
                assertEquals(MESSAGE_ID, controller.lastReadMessageId)
            } finally {
                release.countDown()
                closeFixture(fixture)
            }
        }

    @Test
    fun overlappingReadFailuresCannotSuppressRetryOfEarlierMessage() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val releaseFirst = CountDownLatch(1)
            val releaseSecond = CountDownLatch(1)
            val calls = AtomicInteger()
            val newerId = "cc".repeat(32)
            val row = overlappingUnreadRow(newerId)
            val fixture =
                fixture(row) {
                    when (calls.incrementAndGet()) {
                        1 -> {
                            firstStarted.complete(Unit)
                            check(releaseFirst.await(15, TimeUnit.SECONDS))
                            error("earlier read rejected")
                        }
                        2 -> {
                            secondStarted.complete(Unit)
                            check(releaseSecond.await(15, TimeUnit.SECONDS))
                            error("later read rejected")
                        }
                        else ->
                            row.copy(
                                lastReadMessageIdHex = MESSAGE_ID,
                                lastReadTimelineAt = 2uL,
                                unreadCount = 1uL,
                                firstUnreadMessageIdHex = newerId,
                            )
                    }
                }
            try {
                fixture.bootstrap()
                attachChats(fixture.appState, row)
                val controller = controller(fixture.appState, row)
                val first = async { controller.markReadUpTo(MESSAGE_ID) }
                firstStarted.await()
                val second = async { controller.markReadUpTo(newerId) }
                secondStarted.await()
                assertEquals(2, fixture.markReadCalls.get())
                releaseFirst.countDown()
                first.await()
                assertFalse(second.isCompleted)
                releaseSecond.countDown()
                second.await()
                assertEquals(ConversationTimelineTestIds.MESSAGE_A, controller.lastReadMessageId)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(3, fixture.markReadCalls.get())
                assertEquals(listOf(MESSAGE_ID, newerId, MESSAGE_ID), fixture.markReadRequests.map { it.third })
            } finally {
                releaseFirst.countDown()
                releaseSecond.countDown()
                closeFixture(fixture)
            }
        }

    @Test
    fun readingSavedWatermarkPreservesNewerUnreadMessages() =
        runBlocking {
            val newerId = "cc".repeat(32)
            val reminder =
                reminderRow().let { row ->
                    row.copy(
                        lastMessage = row.lastMessage!!.copy(messageIdHex = newerId, timelineAt = 3uL),
                        unreadCount = 1uL,
                        firstUnreadMessageIdHex = newerId,
                    )
                }
            val read = reminder.copy(manuallyMarkedUnread = false)
            val fixture = fixture(reminder) { read }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, reminder)
                chats.setChatListVisible(false)
                controller(fixture.appState, reminder).markReadUpTo(MESSAGE_ID)
                chats.setChatListVisible(true)
                val returned = chats.items.single().projection!!
                assertEquals(1, fixture.markReadCalls.get())
                assertFalse(returned.manuallyMarkedUnread)
                assertTrue(returned.hasUnread)
                assertEquals(1uL, returned.unreadCount)
                assertEquals(newerId, returned.firstUnreadMessageIdHex)
                assertEquals(MESSAGE_ID, returned.lastReadMessageIdHex)
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun failedFirstVisibleReadCanRetryTheSavedWatermark() =
        runBlocking {
            var attempts = 0
            val reminder = reminderRow()
            val fixture =
                fixture(reminder) {
                    attempts += 1
                    if (attempts == 1) error("read rejected")
                    reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
                }
            try {
                fixture.bootstrap()
                attachChats(fixture.appState, reminder)
                val controller = controller(fixture.appState, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(MESSAGE_ID, controller.lastReadMessageId)
                controller.markReadUpTo(MESSAGE_ID)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun missingVisibleMessageDoesNotFabricateAReadRequest() =
        runBlocking {
            val reminder =
                reminderRow().copy(
                    lastMessage = null,
                    lastReadMessageIdHex = null,
                    lastReadTimelineAt = null,
                )
            val fixture = fixture(reminder) { error("must not mark invisible content read") }
            try {
                fixture.bootstrap()
                attachChats(fixture.appState, reminder)
                val controller = controller(fixture.appState, reminder)
                controller.markReadUpTo("")
                controller.markReadUpTo("optimistic-message")
                assertEquals(0, fixture.markReadCalls.get())
                assertEquals(null, controller.lastReadMessageId)
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun explicitMarkReadClearsEmptyChatManualReminderWithoutInventingMessageId() =
        runBlocking {
            val reminder =
                reminderRow().copy(
                    lastMessage = null,
                    lastReadMessageIdHex = null,
                    lastReadTimelineAt = null,
                )
            val read = reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
            val fixture = fixture(reminder, onManualUnread = { read }) { error("no message read is available") }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, reminder)
                val item = chats.items.single()
                chats.setChatListVisible(false)
                assertTrue(chats.markAllRead(item))
                chats.setChatListVisible(true)
                val returned = chats.items.single().projection!!
                assertFalse(returned.manuallyMarkedUnread)
                assertFalse(returned.hasUnread)
                assertEquals(null, returned.lastReadMessageIdHex)
                assertEquals(null, returned.lastReadTimelineAt)
                assertEquals(0, fixture.markReadCalls.get())
                assertEquals(
                    listOf(Triple(ConversationTimelineTestIds.ACCOUNT_REF, reminder.groupIdHex, false)),
                    fixture.manualUnreadWrites,
                )
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun clearingReminderWithoutKnownTailPreservesNativeUnreadCountAndWatermark() =
        runBlocking {
            val reminder = reminderRow().copy(lastMessage = null, unreadCount = 1uL)
            val read = reminder.copy(manuallyMarkedUnread = false)
            val fixture = fixture(reminder, onManualUnread = { read }) { error("no known message to read") }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, reminder)
                val item = chats.items.single()
                chats.setChatListVisible(false)
                assertTrue(chats.markAllRead(item))
                chats.setChatListVisible(true)
                val returned = chats.items.single().projection!!
                assertFalse(returned.manuallyMarkedUnread)
                assertTrue(returned.hasUnread)
                assertEquals(1uL, returned.unreadCount)
                assertEquals(MESSAGE_ID, returned.lastReadMessageIdHex)
                assertEquals(reminder.lastReadTimelineAt, returned.lastReadTimelineAt)
                assertEquals(0, fixture.markReadCalls.get())
                assertEquals(1, fixture.manualUnreadWrites.size)
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun explicitMarkReadWithoutTailOrManualReminderRemainsANoOp() =
        runBlocking {
            val row = reminderRow().copy(lastMessage = null, manuallyMarkedUnread = false, hasUnread = false)
            val fixture =
                fixture(row, onManualUnread = { error("no reminder to clear") }) {
                    error("no message to read")
                }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, row)
                assertFalse(chats.markAllRead(chats.items.single()))
                assertTrue(fixture.manualUnreadWrites.isEmpty())
                assertEquals(0, fixture.markReadCalls.get())
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun hiddenRetainedControllerCannotConsumeNewManualReminder() =
        runBlocking {
            val reminder = reminderRow()
            val read = reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
            val fixture = fixture(reminder) { read }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, reminder)
                val controller = controller(fixture.appState, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                fixture.appState.clearActiveConversation()
                chats.applyChatListRow(reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                assertTrue(chats.chatItemForGroup(reminder.groupIdHex)!!.projection!!.manuallyMarkedUnread)
                fixture.appState.setActiveConversationFromUi(
                    ConversationTimelineTestIds.ACCOUNT_REF,
                    reminder.groupIdHex,
                )
                fixture.appState.setAppInForeground(false)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                fixture.appState.setAppInForeground(true, dismissRetainedVisibleConversation = false)
                showAppLock(fixture.appState)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                fixture.appState.markAppUnlockSucceeded(dismissRetainedVisibleConversation = false)
                fixture.appState.setActiveConversationFromUi("other-account", reminder.groupIdHex)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                fixture.appState.setActiveConversationFromUi(
                    ConversationTimelineTestIds.ACCOUNT_REF,
                    reminder.groupIdHex,
                )
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun nativeReminderWithoutBoundChatListIsConsumedOnceEvenWhenReadReturnsNull() =
        runBlocking {
            val reminder = reminderRow()
            val read = reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
            val fixture = fixture(reminder) { null }
            try {
                fixture.bootstrap()
                val controller = controller(fixture.appState, read)
                controller.markReadUpTo(MESSAGE_ID)
                controller.applyAuthoritativeChatListRow(ConversationTimelineTestIds.ACCOUNT_REF, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())
                controller.applyAuthoritativeChatListRow(ConversationTimelineTestIds.ACCOUNT_REF, read)
                controller.applyAuthoritativeChatListRow(ConversationTimelineTestIds.ACCOUNT_REF, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(3, fixture.markReadCalls.get())
                assertTrue(fixture.markReadRequests.all { it.third == MESSAGE_ID })
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun firstAndRetriedReadsWaitForVisibleConversation() =
        runBlocking {
            val reminder = reminderRow()
            val calls = AtomicInteger()
            val fixture =
                fixture(reminder) {
                    if (calls.incrementAndGet() == 1) error("first visible read failed")
                    reminder.copy(manuallyMarkedUnread = false, hasUnread = false)
                }
            try {
                fixture.bootstrap()
                val chats = attachChats(fixture.appState, reminder)
                val controller = controller(fixture.appState, reminder)
                fixture.appState.clearActiveConversation()
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(0, fixture.markReadCalls.get())
                activateConversation(fixture.appState, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                fixture.appState.clearActiveConversation()
                chats.applyChatListRow(reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(1, fixture.markReadCalls.get())
                assertTrue(chats.chatItemForGroup(reminder.groupIdHex)!!.projection!!.manuallyMarkedUnread)
                activateConversation(fixture.appState, reminder)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(2, fixture.markReadCalls.get())
                assertFalse(chats.chatItemForGroup(reminder.groupIdHex)!!.projection!!.manuallyMarkedUnread)
            } finally {
                closeFixture(fixture)
            }
        }

    @Test
    fun failedReadWithoutBoundChatListRestoresMostRecentlyConfirmedWatermark() =
        runBlocking {
            val newerId = "cc".repeat(32)
            val row = overlappingUnreadRow(newerId)
            val read =
                row.copy(
                    lastReadMessageIdHex = MESSAGE_ID,
                    lastReadTimelineAt = 2uL,
                    unreadCount = 1uL,
                    firstUnreadMessageIdHex = newerId,
                )
            val calls = AtomicInteger()
            val fixture =
                fixture(row) {
                    when (calls.incrementAndGet()) {
                        1 -> read
                        2 -> error("later read failed")
                        else ->
                            read.copy(
                                lastReadMessageIdHex = newerId,
                                lastReadTimelineAt = 3uL,
                                unreadCount = 0uL,
                                hasUnread = false,
                                firstUnreadMessageIdHex = null,
                            )
                    }
                }
            try {
                fixture.bootstrap()
                val controller = controller(fixture.appState, row)
                controller.markReadUpTo(MESSAGE_ID)
                assertEquals(MESSAGE_ID, controller.latestChatListRow!!.lastReadMessageIdHex)
                controller.markReadUpTo(newerId)
                assertEquals(MESSAGE_ID, controller.lastReadMessageId)
                assertEquals(1uL, controller.latestChatListRow!!.unreadCount)
                controller.markReadUpTo(newerId)
                assertEquals(newerId, controller.lastReadMessageId)
                assertEquals(listOf(MESSAGE_ID, newerId, newerId), fixture.markReadRequests.map { it.third })
            } finally {
                closeFixture(fixture)
            }
        }

    private fun activateConversation(
        state: WhiteNoiseAppState,
        row: ChatListRowFfi,
    ) {
        state.setActiveConversationFromUi(ConversationTimelineTestIds.ACCOUNT_REF, row.groupIdHex)
    }

    private fun showAppLock(state: WhiteNoiseAppState) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Shadow
            .extract<ShadowBiometricManager>(
                context.getSystemService(android.hardware.biometrics.BiometricManager::class.java),
            ).setCanAuthenticate(true)
        state.updateRequireAppUnlock(true)
        assertTrue(state.appLockScreenVisible)
    }

    private fun overlappingUnreadRow(newerId: String): ChatListRowFfi {
        val reminder = reminderRow()
        return reminder.copy(
            lastMessage = reminder.lastMessage!!.copy(messageIdHex = newerId, timelineAt = 3uL),
            lastReadMessageIdHex = ConversationTimelineTestIds.MESSAGE_A,
            lastReadTimelineAt = 1uL,
            manuallyMarkedUnread = false,
            unreadCount = 2uL,
            firstUnreadMessageIdHex = MESSAGE_ID,
        )
    }

    private fun reminderRow() =
        notificationChatListRow().copy(
            unreadCount = 0uL,
            hasUnread = true,
            manuallyMarkedUnread = true,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = MESSAGE_ID,
            lastReadTimelineAt = 2uL,
        )

    private fun fixture(
        row: ChatListRowFfi,
        onManualUnread: ((Boolean) -> ChatListRowFfi?)? = null,
        onRead: () -> ChatListRowFfi?,
    ) = NotificationBootstrapTestFixture(
        context = ApplicationProvider.getApplicationContext(),
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
        chatListRows = listOf(row),
        chatGroups = listOf(conversationTimelineTestGroup()),
        emitStartupNotification = false,
        onMarkTimelineMessageRead = onRead,
        onSetChatManuallyUnread = onManualUnread,
    )

    private fun controller(
        state: WhiteNoiseAppState,
        row: ChatListRowFfi,
    ) = ConversationController(
        appState = state,
        initialGroup = conversationTimelineTestGroup(),
        initialMemberSnapshot = conversationTimelineMemberSnapshot(),
        initialChatListRow = row,
        initialTimelinePreview = row.lastMessage,
        accountRefOverride = ConversationTimelineTestIds.ACCOUNT_REF,
    ).also { controller ->
        mountedConversations += controller
        state.attachConversationController(controller)
        state.setAppInForeground(true, dismissRetainedVisibleConversation = false)
        state.setActiveConversationFromUi(ConversationTimelineTestIds.ACCOUNT_REF, row.groupIdHex)
    }

    private fun attachChats(
        state: WhiteNoiseAppState,
        row: ChatListRowFfi,
    ): ChatsController =
        ChatsController(
            appState = state,
            initialAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            memberSnapshotLoader = { _, _ -> emptyList() },
        ).also { chats ->
            mountedChats += chats
            chats.setChatListVisible(false)
            chats.applyChatListRow(row)
            chats.setChatListVisible(true)
            state.attachChatsController(chats)
        }

    private fun closeFixture(fixture: NotificationBootstrapTestFixture) {
        fixture.appState.clearActiveConversation()
        fixture.appState.attachChatsController(null)
        mountedConversations.forEach(fixture.appState::detachConversationController)
        mountedConversations.clear()
        mountedChats.forEach(ChatsController::onCleared)
        mountedChats.clear()
        fixture.close()
    }

    private companion object {
        val MESSAGE_ID = ConversationTimelineTestIds.MESSAGE_B
    }
}
