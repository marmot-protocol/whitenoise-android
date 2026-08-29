package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Compose skips a row only when the new projection compares equal to the one
 * already composed. `ChatsController.recompute()` rebuilds every row on every
 * engine update, so a projection that is not value-equal to its predecessor
 * recomposes the whole visible list even when nothing about the row changed.
 *
 * MDK's Markdown AST carries a `ByteArray`, and a UniFFI data class compares
 * an array by identity, so allocating a fresh empty document per projection
 * silently broke that equality. These tests pin the property rather than the
 * implementation detail: two projections of identical inputs must be equal.
 */
class ChatListProjectionEqualityTest {
    /**
     * The reason [EMPTY_MARKDOWN_DOCUMENT] is shared rather than rebuilt. A
     * Kotlin data class compares an array property by reference, so two empty
     * documents built separately are unequal. If this ever starts failing,
     * Kotlin (or the binding generator) changed the rule and the shared
     * instance is no longer load-bearing for equality.
     */
    @Test
    fun separatelyBuiltEmptyMarkdownDocumentsAreNotEqual() {
        val first = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))
        val second = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))

        assertNotEquals(first, second)
        assertEquals(EMPTY_MARKDOWN_DOCUMENT, EMPTY_MARKDOWN_DOCUMENT)
    }

    @Test
    fun repeatedProjectionOfAnUnchangedRowIsValueEqual() {
        val row = row(lastMessage = preview())
        val members = members()

        val first = project(row, members)
        val second = project(row, members)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun repeatedProjectionWithoutALastMessageIsValueEqual() {
        val row = row(lastMessage = null)
        val members = members()

        assertEquals(project(row, members), project(row, members))
    }

    @Test
    fun repeatedProjectionOfAnUnchangedRowWithoutARosterIsValueEqual() {
        val row = row(lastMessage = preview())

        assertEquals(project(row, null), project(row, null))
    }

    private fun project(
        row: ChatListRowFfi,
        members: List<AppGroupMemberRecordFfi>?,
    ) = chatListItemFromProjection(
        row = row,
        group = group(),
        activeAccountIdHex = ACTIVE_ACCOUNT,
        members = members,
    )

    private fun members(): List<AppGroupMemberRecordFfi> =
        listOf(
            AppGroupMemberRecordFfi(memberIdHex = ACTIVE_ACCOUNT, account = null, local = true),
            AppGroupMemberRecordFfi(memberIdHex = PEER_ACCOUNT, account = null, local = false),
        )

    private fun preview() =
        ChatListMessagePreviewFfi(
            messageIdHex = "b".repeat(64),
            sender = PEER_ACCOUNT,
            senderDisplayName = null,
            plaintext = "hello",
            contentTokens = EMPTY_MARKDOWN_DOCUMENT,
            kind = 9uL,
            timelineAt = 1_700_000_000uL,
            deleted = false,
            attachmentKind = null,
            attachmentCount = 0u,
            deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED,
        )

    private fun row(lastMessage: ChatListMessagePreviewFfi?) =
        ChatListRowFfi(
            groupIdHex = GROUP_ID,
            pinned = false,
            pinnedPosition = null,
            archived = false,
            pendingConfirmation = false,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
            title = "Conversation",
            groupName = "Conversation",
            avatarUrl = null,
            avatar = null,
            lastMessage = lastMessage,
            unreadCount = 0uL,
            hasUnread = false,
            manuallyMarkedUnread = false,
            unreadMentionCount = 0uL,
            unreadMention = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 1_699_999_000uL,
            activitySortAt = 1_700_000_000uL,
            updatedAt = 1_700_000_000uL,
            selfMembership = SelfMembershipFfi.MEMBER,
            conversationKind = ChatConversationKindFfi.GROUP,
            muted = false,
            mutedUntilMs = null,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "endpoint",
            profilePresent = true,
            name = "Conversation",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$GROUP_ID",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )

    private companion object {
        val GROUP_ID = "a".repeat(64)
        val ACTIVE_ACCOUNT = "c".repeat(64)
        val PEER_ACCOUNT = "d".repeat(64)
    }
}
