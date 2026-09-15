package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A named group must never wear a member's photo in the chat list. MDK resolves
 * a peer profile picture for two-person conversations, and adopting it whole
 * put a contact's avatar on a group the user had just created with that one
 * contact, while the conversation header still drew the group monogram.
 */
class ChatListPeerAvatarTest {
    private val peerAvatar = "https://example.invalid/peer.png"

    /** A named two-member group keeps its own (absent) avatar instead of the peer's photo. */
    @Test
    fun namedGroupIgnoresPeerSourcedAvatar() {
        val item = item(groupName = "Design review", avatarSource = PresentationSourceFfi.PEER_PROFILE)

        assertNull(item.group.avatarUrl)
    }

    /** An unnamed pair is a direct chat, so the peer's picture is the row's avatar. */
    @Test
    fun unnamedPairAdoptsPeerSourcedAvatar() {
        val item = item(groupName = "", avatarSource = PresentationSourceFfi.PEER_PROFILE)

        assertEquals(peerAvatar, item.group.avatarUrl)
    }

    /** A pending welcome shows the inviter, named group or not. */
    @Test
    fun pendingInviteAdoptsPeerSourcedAvatar() {
        val item =
            item(
                groupName = "Design review",
                avatarSource = PresentationSourceFfi.PEER_PROFILE,
                pendingConfirmation = true,
            )

        assertEquals(peerAvatar, item.group.avatarUrl)
    }

    /** A group's own remote image is adopted whatever the group is called. */
    @Test
    fun namedGroupKeepsGroupSourcedAvatar() {
        val item = item(groupName = "Design review", avatarSource = PresentationSourceFfi.GROUP)

        assertEquals(peerAvatar, item.group.avatarUrl)
    }

    private fun item(
        groupName: String,
        avatarSource: PresentationSourceFfi,
        pendingConfirmation: Boolean = false,
    ): ChatListItem =
        chatListItemFromProjection(
            row = row(groupName = groupName, pendingConfirmation = pendingConfirmation),
            selectedPresentation = presentation(groupName, avatarSource),
            group = group(name = groupName, pendingConfirmation = pendingConfirmation),
            activeAccountIdHex = ME,
            members = listOf(member(ME, local = true), member(PEER, local = false)),
        )

    private fun presentation(
        groupName: String,
        avatarSource: PresentationSourceFfi,
    ) = ConversationPresentationFfi(
        title =
            if (groupName.isBlank()) {
                PresentationTextFfi.UnnamedGroup(2uL)
            } else {
                PresentationTextFfi.Literal(groupName)
            },
        avatar = SelectedAvatarFfi.RemoteImage(peerAvatar, "peer-cache-key"),
        titleSource = PresentationSourceFfi.GROUP,
        avatarSource = avatarSource,
        peerId = PEER,
        resolution = PresentationResolutionFfi.CACHED,
    )

    private fun member(
        accountIdHex: String,
        local: Boolean,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = accountIdHex,
        account = if (local) accountIdHex else null,
        local = local,
    )

    private fun row(
        groupName: String,
        pendingConfirmation: Boolean,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = GROUP_ID,
        archived = false,
        pendingConfirmation = pendingConfirmation,
        title = groupName,
        groupName = groupName,
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
        conversationKind = ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

    private fun group(
        name: String,
        pendingConfirmation: Boolean,
    ) = AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = GROUP_ID,
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint",
        name = name,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$GROUP_ID",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia = encryptedMedia(),
        archived = false,
        pendingConfirmation = pendingConfirmation,
        unrecoverable = false,
        welcomerAccountIdHex = if (pendingConfirmation) PEER else null,
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
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
        )

    private companion object {
        const val GROUP_ID = "peer-avatar-group"
        val ME = "a".repeat(64)
        val PEER = "b".repeat(64)
    }
}
