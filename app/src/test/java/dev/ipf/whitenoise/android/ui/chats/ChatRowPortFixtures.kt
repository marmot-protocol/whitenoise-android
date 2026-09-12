package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Fully local, named-group projection: no identity keys, relay work or network-backed avatar in these fixtures. */
internal object ChatRowPortFixtures {
    const val ACCOUNT_REF = "row-test-owner"
    val ACCOUNT_HEX = "a".repeat(64)
    const val TITLE = "Review group"
    const val PREVIEW = "A local message preview long enough to span two lines at the narrow screen width."

    fun state(context: Context): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(AccountSummaryFfi(ACCOUNT_REF, ACCOUNT_HEX, true, false, false, true)),
            activeAccountRef = ACCOUNT_REF,
        )

    fun item(
        membership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
        pinned: Boolean = false,
        unread: Boolean = false,
        preview: String = PREVIEW,
        retentionSeconds: ULong = 0uL,
        delivery: ChatListMessageDeliveryStateFfi = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
        pending: Boolean = false,
    ): ChatListItem {
        val group =
            groupRecord(membership).copy(
                name = TITLE,
                profilePresent = true,
                disappearingMessageSecs = retentionSeconds,
                pendingConfirmation = pending,
            )
        val projected =
            projectedRow(membership).copy(
                title = TITLE,
                groupName = TITLE,
                pinned = pinned,
                unreadCount = if (unread) 3uL else 0uL,
                hasUnread = unread,
                pendingConfirmation = pending,
                conversationKind = ChatConversationKindFfi.GROUP,
                lastMessage =
                    ChatListMessagePreviewFfi(
                        messageIdHex = "message-row-fixture",
                        sender = "b".repeat(64),
                        senderDisplayName = "Test peer",
                        plaintext = preview,
                        contentTokens =
                            MarkdownDocumentFfi(
                                blocks = emptyList(),
                                truncated = false,
                                blankLinesBefore = ByteArray(0),
                            ),
                        kind = 9uL,
                        timelineAt = (System.currentTimeMillis() / 1000).toULong(),
                        deleted = false,
                        attachmentKind = null,
                        attachmentCount = 0u,
                        deliveryState = delivery,
                    ),
            )
        return ChatListItem(group, null, null, 3, null, projection = projected)
    }

    private fun projectedRow(selfMembership: SelfMembershipFfi) =
        ChatListRowFfi(
            selfMembership = selfMembership,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = "g1",
            archived = false,
            pendingConfirmation = false,
            title = "Group",
            groupName = "",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 0uL,
            updatedAt = 1uL,
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

    private fun groupRecord(selfMembership: SelfMembershipFfi) =
        AppGroupRecordFfi(
            selfMembership = selfMembership,
            groupIdHex = "g1",
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint-g1",
            name = "",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-g1",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net"),
                ),
        )
}
