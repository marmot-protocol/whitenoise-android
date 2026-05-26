package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
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

    private fun item(id: String, latestAt: ULong?): ChatListItem {
        return ChatListItem(
            group = group(id),
            latest = latestAt?.let { message(groupId = id, recordedAt = it) },
            otherMemberAccount = null,
            memberCount = 0,
            memberSnapshot = null,
        )
    }

    private fun group(id: String) = AppGroupRecordFfi(
        groupIdHex = id,
        endpoint = "endpoint-$id",
        name = "",
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$id",
        archived = false,
    )

    private fun message(groupId: String, recordedAt: ULong) = AppMessageRecordFfi(
        messageIdHex = "message-$groupId",
        direction = "received",
        groupIdHex = groupId,
        sender = "sender",
        plaintext = "hello",
        kind = 9uL,
        tags = emptyList(),
        recordedAt = recordedAt,
        receivedAt = recordedAt,
    )
}
