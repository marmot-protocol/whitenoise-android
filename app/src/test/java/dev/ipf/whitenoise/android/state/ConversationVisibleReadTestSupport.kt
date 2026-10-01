package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import org.junit.Assert.assertTrue
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

/** Shared real-controller fixture; each regression class remains independently bounded. */
internal abstract class ConversationVisibleReadTestSupport {
    private val mountedChats = mutableListOf<ChatsController>()
    private val mountedConversations = mutableListOf<ConversationController>()

    /** Transfers platform visibility to the fixture account and group without creating a read acknowledgement. */
    protected fun activateConversation(
        state: WhiteNoiseAppState,
        row: ChatListRowFfi,
    ) {
        state.setActiveConversationFromUi(ConversationTimelineTestIds.ACCOUNT_REF, row.groupIdHex)
    }

    /** Enables a working biometric fixture and verifies the lock screen actually gates visible reads. */
    protected fun showAppLock(state: WhiteNoiseAppState) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Shadow
            .extract<ShadowBiometricManager>(
                context.getSystemService(android.hardware.biometrics.BiometricManager::class.java),
            ).setCanAuthenticate(true)
        state.updateRequireAppUnlock(true)
        assertTrue(state.appLockScreenVisible)
    }

    /** Builds a native row with two unread messages and an older persisted cursor for overlapping requests. */
    protected fun overlappingUnreadRow(newerId: String): ChatListRowFfi {
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

    /** Builds manual attention at an already-read watermark with zero actual unread messages. */
    protected fun reminderRow() =
        notificationChatListRow().copy(
            unreadCount = 0uL,
            hasUnread = true,
            manuallyMarkedUnread = true,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = MESSAGE_ID,
            lastReadTimelineAt = 2uL,
        )

    /** Installs controllable native read and manual-flag responses without emitting a startup notification. */
    protected fun fixture(
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

    /** Mounts an account-bound conversation and grants foreground ownership; retains it for cleanup. */
    protected fun controller(
        state: WhiteNoiseAppState,
        row: ChatListRowFfi,
        clockMillis: () -> Long = System::currentTimeMillis,
    ) = ConversationController(
        appState = state,
        initialGroup = conversationTimelineTestGroup(),
        initialMemberSnapshot = conversationTimelineMemberSnapshot(),
        initialChatListRow = row,
        initialTimelinePreview = row.lastMessage,
        accountRefOverride = ConversationTimelineTestIds.ACCOUNT_REF,
        clockMillis = clockMillis,
    ).also { controller ->
        mountedConversations += controller
        state.attachConversationController(controller)
        state.setAppInForeground(true, dismissRetainedVisibleConversation = false)
        state.setActiveConversationFromUi(ConversationTimelineTestIds.ACCOUNT_REF, row.groupIdHex)
    }

    /** Mounts a matching list from a native row through its normal hidden/visible handoff. */
    protected fun attachChats(
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

    /** Removes visibility, detaches controllers and closes native fixture resources between regressions. */
    protected fun closeFixture(fixture: NotificationBootstrapTestFixture) {
        fixture.appState.clearActiveConversation()
        fixture.appState.attachChatsController(null)
        mountedConversations.forEach(fixture.appState::detachConversationController)
        mountedConversations.clear()
        mountedChats.forEach(ChatsController::onCleared)
        mountedChats.clear()
        fixture.close()
    }

    protected companion object {
        val MESSAGE_ID = ConversationTimelineTestIds.MESSAGE_B
    }
}
