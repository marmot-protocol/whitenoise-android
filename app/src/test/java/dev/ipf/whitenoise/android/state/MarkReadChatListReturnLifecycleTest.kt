package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for issue #1415: the first open/read/return lifecycle must clear
 * chat-list row badges and the per-account aggregate without a second open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class MarkReadChatListReturnLifecycleTest {
    @Test
    fun firstOpenReadReturn_rejectsStaleSubscriptionAndClearsBadges() {
        val appState = testAppState()
        val controller = ChatsController(appState)
        bindAccount(controller)

        controller.setChatListVisible(false)
        controller.applyChatListRow(unreadRow(unreadCount = 3uL))
        controller.setChatListVisible(true)
        assertEquals(
            3uL,
            controller.items
                .single()
                .projection
                ?.unreadCount,
        )
        assertEquals(3uL, appState.unreadCountForAccount(ACCOUNT_REF))

        controller.setChatListVisible(false)
        controller.applyChatListRow(readThroughTailRow())
        applySubscriptionRow(
            controller,
            unreadRow(unreadCount = 3uL).copy(
                lastReadTimelineAt = TAIL_AT,
                lastReadMessageIdHex = TAIL_ID,
            ),
            ChatListUpdateTriggerFfi.UNREAD_CHANGED,
        )

        controller.setChatListVisible(true)

        assertEquals(
            0uL,
            controller.items
                .single()
                .projection
                ?.unreadCount,
        )
        assertEquals(0uL, appState.unreadCountForAccount(ACCOUNT_REF))
    }

    private fun bindAccount(controller: ChatsController) {
        ChatsController::class.java
            .getDeclaredField("accountRef")
            .apply { isAccessible = true }
            .set(controller, ACCOUNT_REF)
    }

    private fun applySubscriptionRow(
        controller: ChatsController,
        row: ChatListRowFfi,
        trigger: ChatListUpdateTriggerFfi,
    ) {
        ChatsController::class.java
            .getDeclaredMethod("foldChatRow", ChatListRowFfi::class.java, ChatListUpdateTriggerFfi::class.java)
            .apply { isAccessible = true }
            .invoke(controller, row, trigger)
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(MarkReadLifecycleDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun unreadRow(unreadCount: ULong) =
        chatRow(
            unreadCount = unreadCount,
            lastReadTimelineAt = 50uL,
            lastReadMessageIdHex = ANCHOR_ID,
        )

    private fun readThroughTailRow() =
        chatRow(
            unreadCount = 0uL,
            lastReadTimelineAt = TAIL_AT,
            lastReadMessageIdHex = TAIL_ID,
        )

    private fun chatRow(
        unreadCount: ULong,
        lastReadTimelineAt: ULong?,
        lastReadMessageIdHex: String?,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = GROUP_ID,
        archived = false,
        pendingConfirmation = false,
        title = "Chat",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage =
            ChatListMessagePreviewFfi(
                messageIdHex = TAIL_ID,
                sender = "sender",
                senderDisplayName = "Sender",
                plaintext = "hello",
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = 9uL,
                timelineAt = TAIL_AT,
                deleted = false,
                attachmentKind = null,
                attachmentCount = 0u,
                deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
            ),
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = TAIL_ID,
        lastReadMessageIdHex = lastReadMessageIdHex,
        lastReadTimelineAt = lastReadTimelineAt,
        conversationCreatedAt = 0uL,
        activitySortAt = TAIL_AT,
        updatedAt = TAIL_AT,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.UNKNOWN,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    private companion object {
        private const val ACCOUNT_REF = "alice"
        private const val ACCOUNT_ID = "account-alice"
        private const val GROUP_ID = "group-unread"
        private const val TAIL_AT_SECONDS = 200L
        private val TAIL_AT = TAIL_AT_SECONDS.toULong()
        private val TAIL_ID = "c".repeat(64)
        private val ANCHOR_ID = "a".repeat(64)
    }
}

private class MarkReadLifecycleDraftPersistence : DraftPersistence {
    private val values = mutableMapOf<String, String>()

    override fun read(): Map<String, String> = values.toMap()

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
