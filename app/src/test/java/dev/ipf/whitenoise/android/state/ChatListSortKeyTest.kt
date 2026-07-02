package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Covers [chatListItemSortKey]'s tie-break derivation directly plus the one
 * ordering property that depends on it inside [sortChatListItems]: the key
 * must track what the UI *shows* as the row title — projected title for named
 * groups, peer account for unnamed ones — and never the raw group hex, or the
 * sort drifts away from the rendered list.
 */
class ChatListSortKeyTest {
    @Test
    fun namedGroupKeysOnProjectedTitleOverRawGroupName() {
        val item =
            item(
                groupId = "aaaa",
                groupName = "Raw Fallback Name",
                projectedTitle = "Projected Title",
            )

        assertEquals("projected title", chatListItemSortKey(item))
    }

    @Test
    fun namedGroupWithoutProjectionFallsBackToLowercasedRawName() {
        val item = item(groupId = "aaaa", groupName = "MiXeD Case Name")

        assertEquals("mixed case name", chatListItemSortKey(item))
    }

    @Test
    fun blankProjectedTitleFallsBackToRawGroupName() {
        val item =
            item(
                groupId = "aaaa",
                groupName = "Real Name",
                projectedTitle = "   ",
            )

        assertEquals("real name", chatListItemSortKey(item))
    }

    @Test
    fun unnamedGroupKeysOnLowercasedPeerAccount() {
        val item =
            item(
                groupId = "aaaa",
                groupName = "",
                otherMemberAccount = "PEER-Account-Hex",
            )

        assertEquals("peer-account-hex", chatListItemSortKey(item))
    }

    @Test
    fun unnamedGroupWithoutPeerKeysOnMemberCountSentinel() {
        val item = item(groupId = "aaaa", groupName = "", memberCount = 5)

        assertEquals("~5", chatListItemSortKey(item))
    }

    @Test
    fun whitespaceOnlyGroupNameRoutesThroughTheUnnamedPath() {
        // Mirrors the display-title gating: a whitespace-only name is not a
        // named group, so the key must come from the peer account, not the
        // blank name (which would collapse every such group onto one key).
        val item =
            item(
                groupId = "aaaa",
                groupName = "   ",
                otherMemberAccount = "peer",
            )

        assertEquals("peer", chatListItemSortKey(item))
    }

    @Test
    fun groupHexNeverLeaksIntoTheSortKey() {
        val named = item(groupId = "feedface", groupName = "Named")
        val unnamedWithPeer = item(groupId = "feedface", groupName = "", otherMemberAccount = "peer")
        val unnamedNoPeer = item(groupId = "feedface", groupName = "", memberCount = 3)

        listOf(named, unnamedWithPeer, unnamedNoPeer).forEach { item ->
            assertFalse(
                "sort key must not contain the group hex",
                chatListItemSortKey(item).contains("feedface"),
            )
        }
    }

    @Test
    fun equalTimestampsTieBreakCaseInsensitivelyByTitle() {
        val beta = item(groupId = "0000-first-by-hex", groupName = "beta", latestAt = 100uL)
        val alpha = item(groupId = "ffff-last-by-hex", groupName = "Alpha", latestAt = 100uL)

        val sorted = sortChatListItems(listOf(beta, alpha))

        assertEquals(listOf("ffff-last-by-hex", "0000-first-by-hex"), sorted.map { it.id })
    }

    // ---- helpers ------------------------------------------------------------

    private fun item(
        groupId: String,
        groupName: String,
        projectedTitle: String? = null,
        otherMemberAccount: String? = null,
        memberCount: Int = 0,
        latestAt: ULong? = null,
    ): ChatListItem =
        ChatListItem(
            group = group(groupId, groupName),
            latest = latestAt?.let { message(groupId, it) },
            otherMemberAccount = otherMemberAccount,
            memberCount = memberCount,
            memberSnapshot = null,
            projection = projectedTitle?.let { row(groupId, groupName, it) },
        )

    private fun row(
        groupId: String,
        groupName: String,
        title: String,
    ) = ChatListRowFfi(
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = title,
        groupName = groupName,
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        updatedAt = 0uL,
    )

    private fun group(
        id: String,
        name: String,
    ) = AppGroupRecordFfi(
        groupIdHex = id,
        endpoint = "endpoint-$id",
        name = name,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$id",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        encryptedMedia = encryptedMedia(),
        archived = false,
        pendingConfirmation = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
    )

    private fun message(
        groupId: String,
        recordedAt: ULong,
    ) = dev.ipf.marmotkit.AppMessageRecordFfi(
        messageIdHex = "message-$groupId",
        direction = "received",
        groupIdHex = groupId,
        sender = "sender",
        plaintext = "hello",
        contentTokens = dev.ipf.marmotkit.MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
        kind = 9uL,
        tags = emptyList(),
        recordedAt = recordedAt,
        receivedAt = recordedAt,
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
}
