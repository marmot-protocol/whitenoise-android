package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for issue #1251: scroll-driven mark-read must fold
 * [markTimelineMessageRead]'s returned [ChatListRowFfi] into the active
 * [ChatsController] so chat-list unread badges and reopen dividers clear
 * without relying on a posted OS notification.
 */
class MarkReadChatListProjectionTest {
    @Test
    fun markReadUpTo_foldsReturnedChatListRowIntoChatsController() {
        val body = controllersSource().readText().kotlinFunctionBody("markReadUpTo")

        assertTrue(
            "markReadUpTo must apply markTimelineMessageRead's returned ChatListRowFfi to the chat list",
            "applyChatListRowFromMarkRead" in body || "foldMarkReadReturnedRow" in body,
        )
        assertFalse(
            "mark-read projection refresh must not depend on mute state",
            "isMuted" in body || "chatMutePreferences" in body,
        )
    }

    @Test
    fun markReadUpTo_successPathDoesNotGateRowFoldOnLastReadMessageId() {
        val body = controllersSource().readText().kotlinFunctionBody("markReadUpTo")

        assertFalse(
            "success path must not gate row fold on trimmed != lastReadMessageId",
            Regex("""trimmed\s*!=\s*lastReadMessageId""").containsMatchIn(body),
        )
    }

    @Test
    fun deferredEarlierMarkReadSuccessStillFoldsAuthoritativeRow() {
        val m1 = "a".repeat(64)
        val m2 = "b".repeat(64)
        var lastReadMessageId: String? = m1
        var persistedLastReadTimelineAt: ULong? = 50uL
        var currentChatRow = unreadChatRow(lastMessageId = m2)
        val folded = mutableListOf<ChatListRowFfi>()

        fun applyFromMarkRead(row: ChatListRowFfi) {
            mergeMarkReadChatListRow(currentChatRow, row)?.let { merged ->
                folded += merged
                currentChatRow = merged
            }
        }

        // B starts after A: dedupe advances to M2 while A's FFI is still in flight.
        val previousBeforeB = lastReadMessageId
        lastReadMessageId = m2
        assertEquals(m1, previousBeforeB)

        val authoritativeA = readChatRow(lastMessageId = m2, readThroughId = m1)
        persistedLastReadTimelineAt =
            foldMarkReadReturnedRow(
                row = authoritativeA,
                persistedLastReadTimelineAt = persistedLastReadTimelineAt,
                applyChatListRow = ::applyFromMarkRead,
            )

        // B fails: dedupe rolls back to M1; A's folded projection must remain.
        if (lastReadMessageId == m2) lastReadMessageId = previousBeforeB

        assertEquals(m1, lastReadMessageId)
        assertEquals(1, folded.size)
        assertEquals(0uL, folded.single().unreadCount)
        assertEquals(m1, folded.single().lastReadMessageIdHex)
        assertEquals(100uL, persistedLastReadTimelineAt)
    }

    @Test
    fun outOfOrderMarkReadSuccessesKeepPersistedTimelineMonotonic() {
        var persistedLastReadTimelineAt: ULong? = 50uL
        var currentChatRow =
            chatRow(
                lastMessageId = "tail",
                unreadCount = 0uL,
                lastReadTimelineAt = 50uL,
                lastReadMessageIdHex = "read-50",
            )
        val appliedRows = mutableListOf<ChatListRowFfi>()

        fun applyFromMarkRead(row: ChatListRowFfi) {
            appliedRows += row
            mergeMarkReadChatListRow(currentChatRow, row)?.let { merged ->
                currentChatRow = merged
            }
        }

        val newerSuccess =
            chatRow(
                lastMessageId = "tail",
                unreadCount = 0uL,
                lastReadTimelineAt = 200uL,
                lastReadMessageIdHex = "read-200",
            )
        persistedLastReadTimelineAt =
            foldMarkReadReturnedRow(
                row = newerSuccess,
                persistedLastReadTimelineAt = persistedLastReadTimelineAt,
                applyChatListRow = ::applyFromMarkRead,
            )

        val olderSuccess =
            chatRow(
                lastMessageId = "tail",
                unreadCount = 3uL,
                lastReadTimelineAt = 100uL,
                lastReadMessageIdHex = "read-100",
            )
        persistedLastReadTimelineAt =
            foldMarkReadReturnedRow(
                row = olderSuccess,
                persistedLastReadTimelineAt = persistedLastReadTimelineAt,
                applyChatListRow = ::applyFromMarkRead,
            )

        assertEquals(2, appliedRows.size)
        assertEquals(200uL, persistedLastReadTimelineAt)
        assertEquals(200uL, currentChatRow.lastReadTimelineAt)
        assertEquals("read-200", currentChatRow.lastReadMessageIdHex)
    }

    @Test
    fun markAllRead_foldsReturnedChatListRowIntoChatsController() {
        val body = controllersSource().readText().kotlinFunctionBody("markAllRead")

        assertTrue(
            "markAllRead must apply markTimelineMessageRead's returned ChatListRowFfi to the chat list",
            "applyChatListRow" in body || "foldChatRow" in body,
        )
    }

    @Test
    fun markNotificationMessageRead_foldsReturnedChatListRowIntoChatsController() {
        val body = appStateSource().readText().kotlinFunctionBody("markNotificationMessageRead")

        assertTrue(
            "notification mark-read must apply markTimelineMessageRead's returned ChatListRowFfi to the chat list",
            "applyChatListRowFromMarkRead" in body,
        )
    }

    @Test
    fun markNotificationMessageRead_schedulesAccountReconciliationOnlyWhenTheRowDidNotFold() {
        val body = appStateSource().readText().kotlinFunctionBody("markNotificationMessageRead")

        assertTrue(
            "a bound-controller fold must remain the single refresh for the active account",
            "if (!applyChatListRowFromMarkRead(account, row))" in body,
        )
        assertTrue(
            "an unfolded mark-read must reconcile the acting account's unread aggregate",
            "reconcileAccountUnreadAfterNotificationMarkRead(account" in body,
        )

        val reconcileBody =
            appStateSource().readText().kotlinFunctionBody("reconcileAccountUnreadAfterNotificationMarkRead")
        assertTrue(
            "the active account's projection owners must stay its only reconcilers",
            "if (accountRef == activeAccountRef) return" in reconcileBody,
        )
    }

    private fun unreadChatRow(lastMessageId: String) =
        chatRow(
            lastMessageId = lastMessageId,
            unreadCount = 2uL,
            lastReadTimelineAt = 50uL,
            lastReadMessageIdHex = "read-50",
        )

    private fun readChatRow(
        lastMessageId: String,
        readThroughId: String,
    ) = chatRow(
        lastMessageId = lastMessageId,
        unreadCount = 0uL,
        lastReadTimelineAt = 100uL,
        lastReadMessageIdHex = readThroughId,
    )

    private fun chatRow(
        lastMessageId: String,
        unreadCount: ULong,
        lastReadTimelineAt: ULong?,
        lastReadMessageIdHex: String?,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = "group",
        archived = false,
        pendingConfirmation = false,
        title = "Chat",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage =
            ChatListMessagePreviewFfi(
                messageIdHex = lastMessageId,
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
                timelineAt = 100uL,
                deleted = false,
                attachmentKind = null,
                attachmentCount = 0u,
                deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
            ),
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = lastMessageId,
        lastReadMessageIdHex = lastReadMessageIdHex,
        lastReadTimelineAt = lastReadTimelineAt,
        conversationCreatedAt = 0uL,
        activitySortAt = 0uL,
        updatedAt = 100uL,
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

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing Controllers.kt source file")

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")
}
