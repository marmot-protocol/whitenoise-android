package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListAttachmentKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Protects edit presentation identity, atomic content/status, and the hidden-list handoff. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OptimisticChatEditPreviewsTest {
    private val tokens = MarkdownDocumentFfi(blocks = emptyList(), truncated = false, blankLinesBefore = ByteArray(0))

    /** Content and status change together; all read/order/identity fields stay native-owned. */
    @Test fun pendingAcceptedAndNativeSettlementPreserveIdentityAndOrder() {
        val edits = OptimisticChatEditPreviews()
        val base =
            row("group", preview("before", deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED))
                .copy(unreadCount = 4uL, unreadMentionCount = 1uL, activitySortAt = 12uL)
        edits.begin(base, "preview-message", "action", "after", tokens)
        val pending = edits.project(base)
        assertEquals("after", pending.lastMessage?.plaintext)
        assertEquals(ChatListMessageDeliveryStateFfi.PENDING, pending.lastMessage?.deliveryState)
        assertEquals(base.copy(lastMessage = pending.lastMessage), pending)
        edits.finish("group", "action", MessageStatus.Sent)
        assertEquals("after", edits.project(base).lastMessage?.plaintext)
        val accepted =
            base.copy(
                lastMessage = requireNotNull(base.lastMessage).copy(plaintext = "after", contentTokens = tokens),
            )
        assertSame(accepted, edits.project(accepted))
        assertSame(base, edits.project(base))
    }

    /** Identical-text retries use action identity and a failed edit exposes the original body. */
    @Test fun olderCompletionCannotUndoRetryOrDiscard() {
        val edits = OptimisticChatEditPreviews()
        val base = row("group", preview("before", deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED))
        edits.begin(base, "preview-message", "one", "after", tokens)
        edits.finish("group", "one", MessageStatus.Failed)
        assertEquals("before", edits.project(base).lastMessage?.plaintext)
        assertEquals(ChatListMessageDeliveryStateFfi.FAILED, edits.project(base).lastMessage?.deliveryState)
        edits.begin(base, "preview-message", "two", "after", tokens)
        edits.finish("group", "one", MessageStatus.Sent)
        edits.discard("group", "one")
        assertEquals(ChatListMessageDeliveryStateFfi.PENDING, edits.project(base).lastMessage?.deliveryState)
        edits.discard("group", "two")
        assertSame(base, edits.project(base))
    }

    /** Older history, pending original sends and deletion never gain an unrelated edit status. */
    @Test fun olderMessageAndPendingOriginalKeepTheirOwnState() {
        val edits = OptimisticChatEditPreviews()
        val base = row("group", preview("before", deliveryState = ChatListMessageDeliveryStateFfi.PENDING))
        edits.begin(base, "older-message", "old", "after", tokens)
        assertSame(base, edits.project(base))
        edits.begin(base, "preview-message", "action", "after", tokens)
        edits.finish("group", "action", MessageStatus.Failed)
        assertEquals(ChatListMessageDeliveryStateFfi.PENDING, edits.project(base).lastMessage?.deliveryState)
        val deleted = base.copy(lastMessage = requireNotNull(base.lastMessage).copy(deleted = true))
        assertSame(deleted, edits.project(deleted))
        assertSame(base, edits.project(base))
    }

    /** A newer selected message and a native later edit supersede the submitted display bridge. */
    @Test fun newerNativeSelectionWins() {
        val edits = OptimisticChatEditPreviews()
        val base = row("group", preview("before"))
        edits.begin(base, "preview-message", "action", "after", tokens)
        edits.finish("group", "action", MessageStatus.Sent)
        val remote = base.copy(lastMessage = requireNotNull(base.lastMessage).copy(plaintext = "new remote edit"))
        assertSame(remote, edits.project(remote))
        edits.begin(base, "preview-message", "two", "after", tokens)
        val newer = base.copy(lastMessage = requireNotNull(base.lastMessage).copy(messageIdHex = "new-message"))
        assertSame(newer, edits.project(newer))
        edits.finish("group", "two", MessageStatus.Failed)
        assertSame(newer, edits.project(newer))
    }

    /** Returning from a hidden list projects the staged edit on its first visible frame; rebinding fences callbacks. */
    @Test fun hiddenListFirstFrameAndAccountResetUseControllerProjection() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val state =
                WhiteNoiseAppState(
                    context,
                    DraftStore.forContext(context),
                    { null },
                    listOf(AccountSummaryFfi("alice", "a".repeat(64), true, false, false, true)),
                    "alice",
                )
            val controller =
                ChatsController(
                    state,
                    initialAccountRef = "alice",
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            try {
                val base = row("group", preview("before", deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED))
                controller.setChatListVisible(false)
                controller.applyChatListRow(base)
                val epoch = controller.bindEpoch
                controller.beginChatEditPreview(epoch, "group", "preview-message", "action", "after", tokens)
                controller.finishChatEditPreview(epoch, "group", "action", MessageStatus.Sent)
                controller.setChatListVisible(true)
                assertEquals("after", controller.items.single().projectedPreviewText())
                controller.setChatListVisible(false)
                controller.applyChatListRow(
                    base.copy(lastMessage = requireNotNull(base.lastMessage).copy(plaintext = "after")),
                )
                controller.setChatListVisible(true)
                assertEquals("after", controller.items.single().projectedPreviewText())
                controller.bind(null)
                controller.finishChatEditPreview(epoch, "group", "action", MessageStatus.Failed)
                assertEquals(emptyList<ChatListItem>(), controller.items)
            } finally {
                controller.onCleared()
            }
        }

    private fun preview(
        plaintext: String,
        kind: ULong = 9uL,
        deleted: Boolean = false,
        attachmentKind: ChatListAttachmentKindFfi? = null,
        attachmentCount: UInt = 0u,
        deliveryState: ChatListMessageDeliveryStateFfi = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
        groupSystem: GroupSystemEventFfi? = null,
    ) = ChatListMessagePreviewFfi(
        retentionSeconds = null,
        retentionExpiresAt = null,
        messageIdHex = "preview-message",
        sender = "sender",
        senderDisplayName = "Sender",
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = kind,
        timelineAt = 10uL,
        deleted = deleted,
        deletionSource = DeletionSourceFfi.UNKNOWN,
        attachmentKind = attachmentKind,
        attachmentCount = attachmentCount,
        groupSystem = groupSystem,
        deliveryState = deliveryState,
    )

    private fun row(
        groupId: String,
        preview: ChatListMessagePreviewFfi,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = "Group $groupId",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = preview,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = 0uL,
        updatedAt = 10uL,
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
}
