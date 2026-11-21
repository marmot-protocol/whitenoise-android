package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a newly linked device orders the chats another of the account's devices added (mdk#3580). */
class LinkedCopyChatOrderTest {
    private val linkedAt = 10_000uL

    /**
     * Every added chat is stamped with the moment it arrived, newer than all of its imported history. Those chats
     * still sort by their last messages, like on the device that added them, while a chat whose own activity is
     * newer than its creation keeps MDK's order.
     */
    @Test
    fun chatsAddedByAnotherDeviceSortByTheirHistoryNotTheirArrival() {
        val older = chatListItemFromProjection(row("older", lastAt = 500uL, createdAt = linkedAt, sortAt = linkedAt))
        val newer = chatListItemFromProjection(row("newer", lastAt = 800uL, createdAt = linkedAt, sortAt = linkedAt))
        val live = chatListItemFromProjection(row("live", lastAt = 12_000uL, createdAt = 0uL, sortAt = 12_000uL))

        assertEquals(800uL, newer.latestAt)
        assertEquals(listOf("live", "newer", "older"), sortChatListItems(listOf(older, live, newer)).map { it.id })
    }

    /** A read position newer than the last message still counts as the chat's activity. */
    @Test
    fun aNewerReadPositionCountsAsActivity() {
        val read = row("read", lastAt = 500uL, createdAt = linkedAt, sortAt = linkedAt).copy(lastReadTimelineAt = 900uL)
        assertEquals(900uL, chatListItemFromProjection(read).latestAt)
    }

    /** A chat with no message yet keeps its creation time, so a fresh chat or invitation still sorts by it. */
    @Test
    fun aChatWithoutMessagesKeepsItsCreationTime() {
        val empty = row("empty", lastAt = null, createdAt = 7_000uL, sortAt = 7_000uL)
        assertEquals(7_000uL, chatListItemFromProjection(empty).latestAt)
    }

    /** A row for [id] whose last message is at [lastAt], created at [createdAt] and sorted by MDK at [sortAt]. */
    private fun row(
        id: String,
        lastAt: ULong?,
        createdAt: ULong,
        sortAt: ULong,
    ): ChatListRowFfi =
        InviteAcceptanceTestData.chatListRow(pending = false).copy(
            groupIdHex = id,
            lastMessage = lastAt?.let { preview(id, it) },
            conversationCreatedAt = createdAt,
            activitySortAt = sortAt,
        )

    /** A plain text preview of chat [id] at [timelineAt]. */
    private fun preview(
        id: String,
        timelineAt: ULong,
    ) = ChatListMessagePreviewFfi(
        retentionSeconds = null,
        retentionExpiresAt = null,
        messageIdHex = "message-$id",
        sender = "sender",
        senderDisplayName = "Sender",
        plaintext = "text",
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = 9uL,
        timelineAt = timelineAt,
        deleted = false,
        deletionSource = DeletionSourceFfi.UNKNOWN,
        attachmentKind = null,
        attachmentCount = 0u,
        groupSystem = null,
        deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
        sticker = null,
    )
}
