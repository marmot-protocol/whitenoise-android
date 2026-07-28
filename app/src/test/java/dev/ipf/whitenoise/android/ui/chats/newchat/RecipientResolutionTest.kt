package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.chatListItemFromProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RecipientResolutionTest {
    @Test
    fun otherMemberProvenanceSurvivesMemberCacheClearingBeforeTap() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val groupId = "dm-from-other-member"
        val pickerItem =
            dmChatItem(
                groupId = groupId,
                activeHex = alice,
                members = listOf(member(alice, local = true), member(bob)),
            ).copy(
                // Preserve only the already-projected counterpart. This is the
                // picker state immediately before the async roster cache clears.
                memberCount = 0,
                memberSnapshot = null,
            )
        val candidate =
            deriveRecipientCandidates(
                chatListItems = listOf(pickerItem),
                activeAccountIdHex = alice,
                displayName = { it },
                npub = { "npub1$it" },
            ).single { it.accountIdHex == bob }
        val currentItem = pickerItem.copy(otherMemberAccount = null)

        val resolved =
            existingDirectChatFromProvenance(
                provenanceGroupIdHex = candidate.existingDmGroupIdHex,
                targetReference = candidate.npub,
                chatListItems = listOf(currentItem),
                activeAccountIdHex = alice,
                equivalentTarget = { other -> other.equals(bob, ignoreCase = true) },
            )

        assertEquals(RecipientSearch.Source.InDm, candidate.source)
        assertEquals(groupId, candidate.existingDmGroupIdHex)
        assertEquals(groupId, resolved?.id)
    }

    @Test
    fun provenanceOpensImplicitDmFromLatestSenderWithoutMemberCache() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val groupId = "dm-from-latest-sender"
        val item =
            dmChatItem(
                groupId = groupId,
                activeHex = alice,
                members = null,
                latestSender = bob,
            )
        val candidate =
            deriveRecipientCandidates(
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                displayName = { it },
                npub = { it },
            ).single { it.accountIdHex == bob }

        val resolved =
            existingDirectChatFromProvenance(
                provenanceGroupIdHex = candidate.existingDmGroupIdHex,
                targetReference = bob,
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                equivalentTarget = { false },
            )

        assertEquals(RecipientSearch.Source.InDm, candidate.source)
        assertEquals(groupId, candidate.existingDmGroupIdHex)
        assertEquals(groupId, resolved?.id)
    }

    @Test
    fun provenanceOpensImplicitDmFromWelcomerWithoutMemberCache() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val groupId = "dm-from-welcomer"
        val item =
            dmChatItem(
                groupId = groupId,
                activeHex = alice,
                members = null,
                welcomer = bob,
            )
        val candidate =
            deriveRecipientCandidates(
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                displayName = { it },
                npub = { it },
            ).single { it.accountIdHex == bob }

        val resolved =
            existingDirectChatFromProvenance(
                provenanceGroupIdHex = candidate.existingDmGroupIdHex,
                targetReference = bob,
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                equivalentTarget = { false },
            )

        assertEquals(RecipientSearch.Source.InDm, candidate.source)
        assertEquals(groupId, candidate.existingDmGroupIdHex)
        assertEquals(groupId, resolved?.id)
    }

    @Test
    fun provenanceRejectsRenamedTwoPersonConversation() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val groupId = "renamed-pair"
        val item =
            dmChatItem(
                groupId = groupId,
                activeHex = alice,
                members = listOf(member(alice, local = true), member(bob)),
                groupName = "Project planning",
                conversationKind = ChatConversationKindFfi.GROUP,
            )

        assertNull(
            existingDirectChatFromProvenance(
                provenanceGroupIdHex = groupId,
                targetReference = bob,
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun provenanceRejectsRenamedDirectProjectionWithoutRoster() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val groupId = "renamed-direct-projection"
        val item =
            dmChatItem(
                groupId = groupId,
                activeHex = alice,
                members = null,
                groupName = "Project planning",
                conversationKind = ChatConversationKindFfi.DIRECT,
                latestSender = bob,
            )

        assertNull(
            existingDirectChatFromProvenance(
                provenanceGroupIdHex = groupId,
                targetReference = bob,
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun provenanceRejectsConversationTargetWasRemovedFrom() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val groupId = "removed-target"
        val item =
            dmChatItem(
                groupId = groupId,
                activeHex = alice,
                members = listOf(member(alice, local = true)),
            )

        assertNull(
            existingDirectChatFromProvenance(
                provenanceGroupIdHex = groupId,
                targetReference = bob,
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun provenanceDoesNotAcceptInDmMetadataWithoutLocalRevalidation() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val staleGroupId = "stale-dm"
        val item =
            dmChatItem(
                groupId = staleGroupId,
                activeHex = alice,
                members = listOf(member(alice, local = true), member(bob)),
                groupName = "Renamed after picker",
                conversationKind = ChatConversationKindFfi.GROUP,
            )

        assertNull(
            existingDirectChatFromProvenance(
                provenanceGroupIdHex = staleGroupId,
                targetReference = bob,
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                equivalentTarget = { false },
            ),
        )
    }

    @Test
    fun deriveRecipientCandidatesRetainsExistingDmGroupProvenance() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val groupId = "picker-dm-group"
        val item =
            dmChatItem(
                groupId = groupId,
                activeHex = alice,
                members = listOf(member(alice, local = true), member(bob)),
            )

        val candidates =
            deriveRecipientCandidates(
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                displayName = { hex -> hex },
                npub = { hex -> "npub1$hex" },
            )

        val bobCandidate = candidates.single { it.accountIdHex == bob }
        assertEquals(RecipientSearch.Source.InDm, bobCandidate.source)
        assertEquals(groupId, bobCandidate.existingDmGroupIdHex)
    }

    @Test
    fun provenanceFastPathDoesNotRequireCreateGroup() {
        val alice = "a".repeat(64)
        val bob = "b".repeat(64)
        val groupId = "warm-up-race"
        val item =
            dmChatItem(
                groupId = groupId,
                activeHex = alice,
                members = listOf(member(alice, local = true), member(bob)),
            )
        val candidate =
            RecipientSearch.Candidate(
                accountIdHex = bob,
                displayName = "Bob",
                npub = "npub1$bob",
                source = RecipientSearch.Source.InDm,
                existingDmGroupIdHex = groupId,
            )

        var fallbackLookups = 0
        val opened =
            resolveNewMessageDirectChat(
                npub = candidate.npub,
                existingDmGroupIdHex = candidate.existingDmGroupIdHex,
                chatListItems = listOf(item),
                activeAccountIdHex = alice,
                npubForHex = { hex -> "npub1$hex" },
                existingDirectChat = {
                    fallbackLookups += 1
                    null
                },
            )

        assertEquals(0, fallbackLookups)
        assertFalse(opened.createRequired)
        assertEquals(groupId, opened.item?.id)
    }

    private fun dmChatItem(
        groupId: String,
        activeHex: String,
        members: List<AppGroupMemberRecordFfi>?,
        groupName: String = "",
        conversationKind: ChatConversationKindFfi = ChatConversationKindFfi.DIRECT,
        latestSender: String? = null,
        welcomer: String? = null,
    ): ChatListItem =
        chatListItemFromProjection(
            row = dmChatRow(groupId, groupName, conversationKind, latestSender),
            group = dmChatGroup(groupId, groupName, welcomer),
            activeAccountIdHex = activeHex,
            members = members,
        )

    private fun dmChatRow(
        groupId: String,
        groupName: String,
        conversationKind: ChatConversationKindFfi,
        latestSender: String?,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = groupName,
        groupName = groupName,
        avatarUrl = null,
        avatar = null,
        lastMessage = latestSender?.let(::dmLatestMessage),
        unreadCount = 0uL,
        hasUnread = false,
        manuallyMarkedUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 1uL,
        activitySortAt = 1uL,
        updatedAt = 1uL,
        conversationKind = conversationKind,
        muted = false,
        mutedUntilMs = null,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
    )

    private fun dmLatestMessage(sender: String) =
        ChatListMessagePreviewFfi(
            messageIdHex = "msg",
            sender = sender,
            senderDisplayName = null,
            plaintext = "hi",
            contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
            kind = 1uL,
            timelineAt = 1uL,
            deleted = false,
            attachmentKind = null,
            attachmentCount = 0u,
            deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
        )

    private fun dmChatGroup(
        groupId: String,
        groupName: String,
        welcomer: String?,
    ) = AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = groupId,
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint",
        name = groupName,
        description = "",
        admins = emptyList(),
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "nostr",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia = encryptedMedia(),
        archived = false,
        pendingConfirmation = false,
        unrecoverable = false,
        welcomerAccountIdHex = welcomer,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
    )

    private fun member(
        id: String,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = id,
        account = id,
        local = local,
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
                listOf(
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
        )
}
