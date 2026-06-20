package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val sorted =
            sortChatListItems(
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
        val sorted =
            sortChatListItems(
                listOf(
                    item("active-chat", latestAt = 50uL),
                    item("pending-invite", latestAt = null, pending = true),
                ),
            )

        assertEquals(listOf("pending-invite", "active-chat"), sorted.map { it.id })
    }

    @Test
    fun unnamedGroupsSortByPeerAccountNotGroupHex() {
        // Two unnamed groups, identical latestAt → tie-break falls through to
        // the title key. The raw group hex must NOT be the key: a peer
        // account is always preferred when present so the sort tracks the
        // display title rather than the cosmetic group id ordering.
        val zeebra =
            ChatListItem(
                group = group("ffff-comes-first-by-hex"),
                latest = message(groupId = "ffff-comes-first-by-hex", recordedAt = 100uL),
                otherMemberAccount = "zeebra-account",
                memberCount = 2,
                memberSnapshot = null,
            )
        val alpha =
            ChatListItem(
                group = group("0000-comes-last-by-hex"),
                latest = message(groupId = "0000-comes-last-by-hex", recordedAt = 100uL),
                otherMemberAccount = "alpha-account",
                memberCount = 2,
                memberSnapshot = null,
            )

        val sorted = sortChatListItems(listOf(zeebra, alpha))

        assertEquals(
            listOf("0000-comes-last-by-hex", "ffff-comes-first-by-hex"),
            sorted.map { it.id },
        )
    }

    @Test
    fun projectedChatListRowCarriesTitlePreviewTimestampAndUnreadState() {
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "group-a",
                    title = "Marmot Lab",
                    preview = "projected latest",
                    latestAt = 20uL,
                    unreadCount = 3uL,
                ),
            )

        assertEquals("Marmot Lab", item.projectedTitle)
        assertEquals("projected latest", item.projectedPreviewText(empty = "empty"))
        assertEquals(20uL, item.latestAt)
        assertEquals(3uL, item.unreadCount)
        assertTrue(item.hasUnread)
    }

    @Test
    fun freshlyCreatedChatWithNoMessagesSortsAboveOlderMessagedChats() {
        // A just-created DM/group has a projection (with `updatedAt` ≈ now) but
        // no last message yet. Its `latestAt` must fall back to `updatedAt` so
        // it sorts to the TOP of the chat list, matching the "most recent
        // activity" ordering, rather than collapsing to 0uL and landing at the
        // bottom (issue #321).
        val olderMessaged =
            chatListItemFromProjection(
                row(
                    groupId = "older-with-message",
                    title = "Old Chat",
                    preview = "an old message",
                    latestAt = 100uL,
                    unreadCount = 0uL,
                ),
            )
        val freshlyCreated =
            chatListItemFromProjection(
                noMessageRow(
                    groupId = "fresh-no-message",
                    title = "New DM",
                    updatedAt = 200uL,
                ),
            )

        assertEquals(200uL, freshlyCreated.latestAt)
        val sorted = sortChatListItems(listOf(olderMessaged, freshlyCreated))
        assertEquals(listOf("fresh-no-message", "older-with-message"), sorted.map { it.id })
    }

    @Test
    fun lastMessageTimestampStillWinsOverProjectionUpdatedAt() {
        // The `updatedAt` fallback must NOT override an existing chat's
        // last-message ordering: a chat with a message keeps sorting on the
        // message's timeline timestamp even when its projection `updatedAt`
        // (bumped by e.g. a read-state or avatar change) is more recent.
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "messaged",
                    title = "Messaged",
                    preview = "hello",
                    latestAt = 50uL,
                    unreadCount = 0uL,
                ).copy(updatedAt = 9999uL),
            )

        assertEquals(50uL, item.latestAt)
    }

    private fun item(
        id: String,
        latestAt: ULong?,
        pending: Boolean = false,
    ): ChatListItem =
        ChatListItem(
            group = group(id, pending = pending),
            latest = latestAt?.let { message(groupId = id, recordedAt = it) },
            otherMemberAccount = null,
            memberCount = 0,
            memberSnapshot = null,
        )

    private fun row(
        groupId: String,
        title: String,
        preview: String,
        latestAt: ULong,
        unreadCount: ULong,
    ) = ChatListRowFfi(
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = title,
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage =
            ChatListMessagePreviewFfi(
                messageIdHex = "message-$groupId",
                sender = "sender",
                senderDisplayName = "Sender",
                plaintext = preview,
                contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                kind = 9uL,
                timelineAt = latestAt,
                deleted = false,
            ),
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = "message-$groupId",
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        updatedAt = latestAt,
    )

    private fun noMessageRow(
        groupId: String,
        title: String,
        updatedAt: ULong,
    ) = ChatListRowFfi(
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = title,
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        updatedAt = updatedAt,
    )

    private fun group(
        id: String,
        pending: Boolean = false,
    ) = AppGroupRecordFfi(
        groupIdHex = id,
        endpoint = "endpoint-$id",
        name = "",
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$id",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        encryptedMedia = encryptedMedia(),
        archived = false,
        pendingConfirmation = pending,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
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
        contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
        kind = 9uL,
        tags = tags,
        recordedAt = recordedAt,
        receivedAt = recordedAt,
    )
}
