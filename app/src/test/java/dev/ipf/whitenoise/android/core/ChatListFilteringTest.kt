package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListFilteringTest {
    private val mlsGroupId = "a1b2c3d4e5f6" + "00".repeat(26)
    private val nostrGroupId = "f0e1d2c3b4a5" + "ff".repeat(26)

    @Test
    fun searchMatchesMlsGroupIdPrefixCaseInsensitive() {
        val target = chatItem(name = "Work Chat", groupIdHex = mlsGroupId, nostrGroupIdHex = nostrGroupId)
        val other = chatItem(name = "Other Chat", groupIdHex = "deadbeef".repeat(8), nostrGroupIdHex = "cafe".repeat(16))

        val results =
            applyChatListSearchAndFilter(
                source = listOf(other, target),
                rawQuery = "A1B2C3",
                filter = ChatListFilter.All,
                displayTitle = { it.group.name },
            )

        assertEquals(listOf(target), results)
    }

    @Test
    fun searchMatchesNostrGroupIdPrefixCaseInsensitive() {
        val target = chatItem(name = "Work Chat", groupIdHex = mlsGroupId, nostrGroupIdHex = nostrGroupId)
        val other = chatItem(name = "Other Chat", groupIdHex = "deadbeef".repeat(8), nostrGroupIdHex = "cafe".repeat(16))

        val results =
            applyChatListSearchAndFilter(
                source = listOf(other, target),
                rawQuery = "F0E1D2",
                filter = ChatListFilter.All,
                displayTitle = { it.group.name },
            )

        assertEquals(listOf(target), results)
    }

    private fun chatItem(
        name: String,
        groupIdHex: String,
        nostrGroupIdHex: String,
    ): ChatListItem {
        val row =
            ChatListRowFfi(
                selfMembership = SelfMembershipFfi.MEMBER,
                unreadMentionCount = 0uL,
                unreadMention = false,
                groupIdHex = groupIdHex,
                archived = false,
                pendingConfirmation = false,
                title = name,
                groupName = name,
                avatarUrl = null,
                avatar = null,
                lastMessage =
                    ChatListMessagePreviewFfi(
                        messageIdHex = "last-message",
                        sender = "peer-acc",
                        senderDisplayName = null,
                        plaintext = "hello",
                        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                        kind = 9uL,
                        timelineAt = 1uL,
                        deleted = false,
                    ),
                unreadCount = 0uL,
                hasUnread = false,
                firstUnreadMessageIdHex = null,
                lastReadMessageIdHex = null,
                lastReadTimelineAt = null,
                updatedAt = 1uL,
            )
        val group =
            AppGroupRecordFfi(
                selfMembership = SelfMembershipFfi.MEMBER,
                groupIdHex = groupIdHex,
                endpoint = "endpoint",
                name = name,
                description = "",
                admins = emptyList(),
                relays = emptyList(),
                nostrGroupIdHex = nostrGroupIdHex,
                avatarUrl = null,
                avatarDim = null,
                avatarThumbhash = null,
                imageHashHex = null,
                encryptedMedia = encryptedMedia(),
                archived = false,
                pendingConfirmation = false,
                welcomerAccountIdHex = null,
                viaWelcomeMessageIdHex = null,
                disappearingMessageSecs = 0uL,
            )
        return ChatListItem(
            group = group,
            latest =
                AppMessageRecordFfi(
                    messageIdHex = "last-message",
                    direction = "received",
                    groupIdHex = groupIdHex,
                    sender = "peer-acc",
                    plaintext = "hello",
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                    kind = 9uL,
                    tags = emptyList(),
                    recordedAt = 1uL,
                    receivedAt = 1uL,
                ),
            otherMemberAccount = null,
            memberCount = 0,
            memberSnapshot = null,
            projection = row,
        )
    }

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
        )
}
