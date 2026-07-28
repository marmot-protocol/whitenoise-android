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
import dev.ipf.whitenoise.android.state.AppliedGroupDetails
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.chatListItemFromProjection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RecipientResolutionTest {
    @Test
    fun provenanceOpensImplicitDmWhenAuthoritativeRosterMatches() =
        runTest {
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)
            val groupId = "dm-from-authoritative-roster"
            val currentItem =
                dmChatItem(
                    groupId = groupId,
                    activeHex = alice,
                    members = null,
                ).copy(memberCount = 0, memberSnapshot = null, otherMemberAccount = null)
            val members = listOf(member(alice, local = true), member(bob))

            val resolved =
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    activeAccountIdHex = alice,
                    equivalentTarget = { other -> other.equals(bob, ignoreCase = true) },
                    chatItemForGroup = { id -> currentItem.takeIf { it.id.equals(id, ignoreCase = true) } },
                    authoritativeGroupDetails = { appliedDetails(currentItem, members) },
                )

            assertEquals(groupId, resolved.item?.id)
        }

    @Test
    fun provenanceRejectsWhenCurrentRowRenamedButStalePickerStillValid() =
        runTest {
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)
            val groupId = "renamed-after-picker"
            val stalePickerItem =
                dmChatItem(
                    groupId = groupId,
                    activeHex = alice,
                    members = listOf(member(alice, local = true), member(bob)),
                )
            val currentRenamedItem =
                dmChatItem(
                    groupId = groupId,
                    activeHex = alice,
                    members = listOf(member(alice, local = true), member(bob)),
                    groupName = "Project planning",
                    conversationKind = ChatConversationKindFfi.GROUP,
                )
            val candidate =
                deriveRecipientCandidates(
                    chatListItems = listOf(stalePickerItem),
                    activeAccountIdHex = alice,
                    displayName = { it },
                    npub = { "npub1$it" },
                ).single { it.accountIdHex == bob }

            val resolved =
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = candidate.existingDmGroupIdHex,
                    targetReference = candidate.npub,
                    activeAccountIdHex = alice,
                    equivalentTarget = { other -> other.equals(bob, ignoreCase = true) },
                    chatItemForGroup = { id -> currentRenamedItem.takeIf { it.id.equals(id, ignoreCase = true) } },
                    authoritativeGroupDetails = {
                        appliedDetails(
                            currentRenamedItem,
                            listOf(member(alice, local = true), member(bob)),
                        )
                    },
                )

            assertEquals(RecipientSearch.Source.InDm, candidate.source)
            assertEquals(groupId, candidate.existingDmGroupIdHex)
            assertNull(resolved.item)
        }

    @Test
    fun provenanceRejectsAuthoritativeRenameBeforeBackingRowCatchesUp() =
        runTest {
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)
            val groupId = "authoritative-rename"
            val staleUnnamedItem =
                dmChatItem(
                    groupId = groupId,
                    activeHex = alice,
                    members = null,
                )

            val resolved =
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                    chatItemForGroup = { staleUnnamedItem },
                    authoritativeGroupDetails = {
                        AppliedGroupDetails(
                            group = staleUnnamedItem.group.copy(name = "Project planning"),
                            members = listOf(member(alice, local = true), member(bob)),
                        )
                    },
                )

            assertNull(resolved.item)
        }

    @Test
    fun provenanceRejectsColdRosterWhenHistoricalSenderPointsToWrongCounterparty() =
        runTest {
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)
            val carol = "c".repeat(64)
            val groupId = "wrong-counterparty"
            val currentItem =
                dmChatItem(
                    groupId = groupId,
                    activeHex = alice,
                    members = null,
                    latestSender = bob,
                )
            val candidate =
                deriveRecipientCandidates(
                    chatListItems = listOf(currentItem),
                    activeAccountIdHex = alice,
                    displayName = { it },
                    npub = { it },
                ).single { it.accountIdHex == bob }

            val resolved =
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = candidate.existingDmGroupIdHex,
                    targetReference = bob,
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                    chatItemForGroup = { id -> currentItem.takeIf { it.id.equals(id, ignoreCase = true) } },
                    authoritativeGroupDetails = {
                        appliedDetails(
                            currentItem,
                            listOf(member(alice, local = true), member(carol)),
                        )
                    },
                )

            assertEquals(RecipientSearch.Source.InDm, candidate.source)
            assertNull(resolved.item)
        }

    @Test
    fun provenanceRejectsWelcomerInferenceWithoutAuthoritativeRoster() =
        runTest {
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

            val resolution =
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                    chatItemForGroup = { id -> item.takeIf { it.id.equals(id, ignoreCase = true) } },
                    authoritativeGroupDetails = { null },
                )

            assertNull(resolution.item)
            assertFalse(resolution.createRequired)
        }

    @Test
    fun provenanceRejectsLatestSenderInferenceWithoutAuthoritativeRoster() =
        runTest {
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

            assertNull(
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                    chatItemForGroup = { id -> item.takeIf { it.id.equals(id, ignoreCase = true) } },
                    authoritativeGroupDetails = { null },
                ).item,
            )
        }

    @Test
    fun provenanceRejectsRenamedTwoPersonConversation() =
        runTest {
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
                resolveFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    chatListItems = listOf(item),
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                ),
            )
        }

    @Test
    fun provenanceRejectsRenamedDirectProjectionWithoutRoster() =
        runTest {
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
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                    chatItemForGroup = { id -> item.takeIf { it.id.equals(id, ignoreCase = true) } },
                    authoritativeGroupDetails = {
                        appliedDetails(
                            item,
                            listOf(member(alice, local = true), member(bob)),
                        )
                    },
                ).item,
            )
        }

    @Test
    fun provenanceRejectsConversationTargetWasRemovedFrom() =
        runTest {
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
                resolveFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    chatListItems = listOf(item),
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                ),
            )
        }

    @Test
    fun provenanceDoesNotAcceptInDmMetadataWithoutLocalRevalidation() =
        runTest {
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
                resolveFromProvenance(
                    provenanceGroupIdHex = staleGroupId,
                    targetReference = bob,
                    chatListItems = listOf(item),
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                ),
            )
        }

    @Test
    fun provenanceRejectsWhenGroupRemovedFromCurrentBackingMap() =
        runTest {
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)
            val groupId = "removed-group"

            val resolution =
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                    chatItemForGroup = { null },
                    authoritativeGroupDetails = { error("must not fetch removed group") },
                )

            assertNull(resolution.item)
            assertFalse(resolution.createRequired)
        }

    @Test
    fun provenanceFailsClosedWhenCurrentRowDisappearsDuringAuthoritativeRead() =
        runTest {
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)
            val groupId = "removed-during-read"
            val item =
                dmChatItem(
                    groupId = groupId,
                    activeHex = alice,
                    members = listOf(member(alice, local = true), member(bob)),
                )
            var rowReads = 0

            val resolution =
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                    chatItemForGroup = {
                        rowReads += 1
                        item.takeIf { rowReads == 1 }
                    },
                    authoritativeGroupDetails = {
                        appliedDetails(item, listOf(member(alice, local = true), member(bob)))
                    },
                )

            assertEquals(2, rowReads)
            assertNull(resolution.item)
            assertFalse(resolution.createRequired)
        }

    @Test
    fun provenanceFailsClosedWhenCurrentRowDisagreesWithValidAuthoritativeDetails() =
        runTest {
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)
            val groupId = "changed-during-read"
            val validItem =
                dmChatItem(
                    groupId = groupId,
                    activeHex = alice,
                    members = listOf(member(alice, local = true), member(bob)),
                )
            val renamedItem =
                validItem.copy(
                    group = validItem.group.copy(name = "Changed while loading"),
                    projection = validItem.projection?.copy(groupName = "Changed while loading"),
                )
            var rowReads = 0

            val resolution =
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                    chatItemForGroup = {
                        rowReads += 1
                        if (rowReads == 1) validItem else renamedItem
                    },
                    authoritativeGroupDetails = {
                        appliedDetails(validItem, listOf(member(alice, local = true), member(bob)))
                    },
                )

            assertEquals(2, rowReads)
            assertNull(resolution.item)
            assertFalse(resolution.createRequired)
        }

    @Test
    fun provenanceRejectsWhenAccountBindChangesDuringAuthoritativeRead() =
        runTest {
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)
            val groupId = "bind-switch"
            val item =
                dmChatItem(
                    groupId = groupId,
                    activeHex = alice,
                    members = listOf(member(alice, local = true), member(bob)),
                )
            var bound = true

            val resolution =
                existingDirectChatFromProvenance(
                    provenanceGroupIdHex = groupId,
                    targetReference = bob,
                    activeAccountIdHex = alice,
                    equivalentTarget = { false },
                    chatItemForGroup = { id -> item.takeIf { it.id.equals(id, ignoreCase = true) } },
                    authoritativeGroupDetails = {
                        bound = false
                        appliedDetails(item, listOf(member(alice, local = true), member(bob)))
                    },
                    accountStillBound = { bound },
                )

            assertNull(resolution.item)
            assertFalse(resolution.createRequired)
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
    fun missingProvenanceOpensCachedDirectChat() =
        runTest {
            val alice = "a".repeat(64)
            val bob = "b".repeat(64)
            val fallbackItem =
                dmChatItem(
                    groupId = "fallback-dm",
                    activeHex = alice,
                    members = listOf(member(alice, local = true), member(bob)),
                )

            val resolution =
                resolveNewMessageDirectChat(
                    npub = "npub1$bob",
                    existingDmGroupIdHex = null,
                    provenanceDirectChat = { _, _ -> error("provenance lookup must not run") },
                    existingDirectChat = { fallbackItem },
                )

            assertFalse(resolution.createRequired)
            assertEquals("fallback-dm", resolution.item?.id)
        }

    @Test
    fun provenanceFastPathDoesNotRequireCreateGroup() =
        runTest {
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
                    provenanceDirectChat = { provenance, target ->
                        existingDirectChatFromProvenance(
                            provenanceGroupIdHex = provenance,
                            targetReference = target,
                            activeAccountIdHex = alice,
                            equivalentTarget = { other -> other.equals(bob, ignoreCase = true) },
                            chatItemForGroup = { id -> item.takeIf { it.id.equals(id, ignoreCase = true) } },
                            authoritativeGroupDetails = {
                                appliedDetails(
                                    item,
                                    listOf(member(alice, local = true), member(bob)),
                                )
                            },
                        )
                    },
                    existingDirectChat = {
                        fallbackLookups += 1
                        null
                    },
                )

            assertEquals(0, fallbackLookups)
            assertFalse(opened.createRequired)
            assertEquals(groupId, opened.item?.id)
        }
}

private suspend fun resolveFromProvenance(
    provenanceGroupIdHex: String?,
    targetReference: String,
    chatListItems: List<ChatListItem>,
    activeAccountIdHex: String?,
    equivalentTarget: (String) -> Boolean,
    authoritativeMembersForGroup: (ChatListItem) -> List<AppGroupMemberRecordFfi>? = { item ->
        item.memberSnapshot?.members
    },
): ChatListItem? =
    existingDirectChatFromProvenance(
        provenanceGroupIdHex = provenanceGroupIdHex,
        targetReference = targetReference,
        activeAccountIdHex = activeAccountIdHex,
        equivalentTarget = equivalentTarget,
        chatItemForGroup = { id -> chatListItems.firstOrNull { it.id.equals(id, ignoreCase = true) } },
        authoritativeGroupDetails = { id ->
            val item =
                chatListItems.firstOrNull { it.id.equals(id, ignoreCase = true) }
                    ?: return@existingDirectChatFromProvenance null
            authoritativeMembersForGroup(item)?.let { appliedDetails(item, it) }
        },
    ).item

private fun appliedDetails(
    item: ChatListItem,
    members: List<AppGroupMemberRecordFfi>,
): AppliedGroupDetails = AppliedGroupDetails(group = item.group, members = members)

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
    pinned = false,
    pinnedPosition = null,
    lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
    disbanding = false,
    disbandRequest = null,
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
    disbanding = false,
    disbanded = false,
    disbandRequest = null,
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
