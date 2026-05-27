package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.MessageUpdateFfi
import dev.ipf.marmotkit.ReceivedMessageFfi
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListSortingTest {
    @Test
    fun chatsWithoutMessagesSortAfterChatsWithMessages() {
        val withLatest = item("with-latest", latestAt = 25uL)
        val withoutLatest = item("without-latest", latestAt = null)

        val sorted = sortChatListItems(listOf(withoutLatest, withLatest))

        assertEquals(listOf("with-latest", "without-latest"), sorted.map { it.id })
    }

    @Test
    fun chatsWithoutMessagesCanSortBesideUnsignedLongMessageTimes() {
        val sorted = sortChatListItems(
            listOf(
                item("no-message", latestAt = null),
                item("newer", latestAt = ULong.MAX_VALUE),
                item("older", latestAt = 1uL),
            ),
        )

        assertEquals(listOf("newer", "older", "no-message"), sorted.map { it.id })
    }

    @Test
    fun pendingInvitesSortBeforeExistingChats() {
        val sorted = sortChatListItems(
            listOf(
                item("active-chat", latestAt = 50uL),
                item("pending-invite", latestAt = null, pending = true),
            ),
        )

        assertEquals(listOf("pending-invite", "active-chat"), sorted.map { it.id })
    }

    @Test
    fun streamedMessageUpdateBecomesLatestWithoutReloadingMessages() {
        val existing = message(groupId = "group-a", recordedAt = 10uL, plaintext = "old")
        val update = MessageUpdateFfi.Message(
            RuntimeMessageReceivedFfi(
                accountIdHex = "account",
                accountLabel = "account-label",
                message = ReceivedMessageFfi(
                    messageIdHex = "message-new",
                    groupIdHex = "group-a",
                    sender = "sender",
                    senderDisplayName = null,
                    plaintext = "streamed",
                    kind = 9uL,
                    tags = emptyList(),
                ),
            ),
        )

        val latest = latestMessagesAfterStreamUpdate(
            latestByGroup = mapOf("group-a" to existing),
            update = update,
            recordedAt = 20uL,
        )

        assertEquals("streamed", latest["group-a"]?.plaintext)
        assertEquals(20uL, latest["group-a"]?.recordedAt)
    }

    private fun item(id: String, latestAt: ULong?, pending: Boolean = false): ChatListItem {
        return ChatListItem(
            group = group(id, pending = pending),
            latest = latestAt?.let { message(groupId = id, recordedAt = it) },
            otherMemberAccount = null,
            memberCount = 0,
            memberSnapshot = null,
        )
    }

    private fun group(id: String, pending: Boolean = false) = AppGroupRecordFfi(
        groupIdHex = id,
        endpoint = "endpoint-$id",
        name = "",
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$id",
        archived = false,
        pendingConfirmation = pending,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

    private fun message(
        groupId: String,
        recordedAt: ULong,
        plaintext: String = "hello",
        tags: List<MessageTagFfi> = emptyList(),
    ) = AppMessageRecordFfi(
        messageIdHex = "message-$groupId",
        direction = "received",
        groupIdHex = groupId,
        sender = "sender",
        plaintext = plaintext,
        kind = 9uL,
        tags = tags,
        recordedAt = recordedAt,
        receivedAt = recordedAt,
    )
}
