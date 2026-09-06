package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the removed-group unread suppression on [ChatListItem]
 * ([ChatListItem.effectiveUnreadCount] / [ChatListItem.effectiveHasUnread],
 * issue #625) and the roster membership predicate behind it
 * ([GroupMemberSnapshot.containsAccount]).
 *
 * The engine freezes a projection's unread count once self is evicted, so a
 * removed group would otherwise show a stale badge forever. Suppression must
 * only fire on *known* removal — the explicit removed marker or a loaded
 * roster that omits self — never on an ambiguous (null/empty) roster.
 */
class ChatListUnreadSuppressionTest {
    @Test
    fun removedMarkerZeroesTheBadgeEvenWhileTheRosterStillContainsSelf() {
        val item = item(unreadCount = 4uL, members = listOf("self", "peer"), removed = true)

        assertEquals(0uL, item.effectiveUnreadCount("self"))
        assertFalse(item.effectiveHasUnread("self"))
    }

    @Test
    fun loadedRosterOmittingSelfZeroesTheBadge() {
        val item = item(unreadCount = 4uL, members = listOf("peer-a", "peer-b"))

        assertTrue(item.removedFromGroup("self"))
        assertEquals(0uL, item.effectiveUnreadCount("self"))
        assertFalse(item.effectiveHasUnread("self"))
    }

    @Test
    fun activeMemberKeepsTheProjectedBadge() {
        val item = item(unreadCount = 4uL, members = listOf("self", "peer"))

        assertFalse(item.removedFromGroup("self"))
        assertEquals(4uL, item.effectiveUnreadCount("self"))
        assertTrue(item.effectiveHasUnread("self"))
    }

    @Test
    fun engineReportedRemovedSelfMembershipSuppressesEvenWhileTheRosterStillContainsSelf() {
        // The engine's authoritative self-membership takes precedence over the
        // roster heuristic: an evicted account whose cached roster hasn't caught
        // up (self still listed) is still removed.
        val item = item(unreadCount = 4uL, members = listOf("self", "peer"), selfMembership = SelfMembershipFfi.REMOVED)

        assertTrue(item.removedFromGroup("self"))
        assertEquals(0uL, item.effectiveUnreadCount("self"))
        assertFalse(item.effectiveHasUnread("self"))
    }

    @Test
    fun engineReportedLeftSelfMembershipSuppressesEvenWhileTheRosterStillContainsSelf() {
        val item = item(unreadCount = 4uL, members = listOf("self", "peer"), selfMembership = SelfMembershipFfi.LEFT)

        assertTrue(item.removedFromGroup("self"))
        assertEquals(0uL, item.effectiveUnreadCount("self"))
        assertFalse(item.effectiveHasUnread("self"))
    }

    @Test
    fun durablyQueuedLeaveSuppressesLikeAnActualLeave() {
        val item = item(unreadCount = 4uL, members = listOf("self", "peer"), leaveRequestPending = true)

        assertTrue(item.removedFromGroup("self"))
        assertEquals(0uL, item.effectiveUnreadCount("self"))
        assertFalse(item.effectiveHasUnread("self"))
    }

    @Test
    fun disbandedLifecycleSuppressesLikeARemoval() {
        val item =
            item(
                unreadCount = 4uL,
                members = listOf("self", "peer"),
                lifecycleState = GroupLifecycleStateFfi.DISBANDED,
            )

        assertTrue(item.removedFromGroup("self"))
        assertEquals(0uL, item.effectiveUnreadCount("self"))
        assertFalse(item.effectiveHasUnread("self"))
    }

    @Test
    fun convergingDisbandSuppressesWhileTheEngineGatesTheGroup() {
        val item = item(unreadCount = 4uL, members = listOf("self", "peer"), disbanding = true)

        assertTrue(item.removedFromGroup("self"))
        assertEquals(0uL, item.effectiveUnreadCount("self"))
        assertFalse(item.effectiveHasUnread("self"))
    }

    @Test
    fun coldOpenFallbackRecordCarriesTheRowsTerminalState() {
        // A projection-only cold open (full group record still loading) must
        // not flash an active composer for an unrecoverable or disbanded chat.
        val unrecoverable =
            emptyGroupRecord(
                row("group-a", 0uL).copy(lifecycleState = GroupLifecycleStateFfi.UNRECOVERABLE),
            )
        assertTrue(unrecoverable.unrecoverable)

        val disbanding = emptyGroupRecord(row("group-a", 0uL).copy(disbanding = true))
        assertTrue(disbanding.disbanding)
        assertFalse(disbanding.disbanded)

        val disbanded =
            emptyGroupRecord(
                row("group-a", 0uL).copy(lifecycleState = GroupLifecycleStateFfi.DISBANDED),
            )
        assertTrue(disbanded.disbanded)
    }

    @Test
    fun nullOrBlankActiveAccountNeverSuppresses() {
        // Matching GroupProjector semantics: with no active account there is
        // no removal to establish — even an explicit removed marker must not
        // fire, so a teardown-window null account can't flicker badges.
        val item = item(unreadCount = 4uL, members = listOf("peer-a"), removed = true)

        listOf(null, "", "   ").forEach { active ->
            assertFalse(item.removedFromGroup(active))
            assertEquals(4uL, item.effectiveUnreadCount(active))
            assertTrue(item.effectiveHasUnread(active))
        }
    }

    @Test
    fun emptyRosterWithoutRemovedMarkerIsAmbiguousAndKeepsTheBadge() {
        // An empty snapshot without the removed marker is a best-effort fetch
        // failure, not removal evidence.
        val item = item(unreadCount = 4uL, members = emptyList())

        assertFalse(item.removedFromGroup("self"))
        assertEquals(4uL, item.effectiveUnreadCount("self"))
        assertTrue(item.effectiveHasUnread("self"))
    }

    @Test
    fun missingRosterWithoutRemovedMarkerKeepsTheBadge() {
        val item = item(unreadCount = 4uL, members = null)

        assertFalse(item.removedFromGroup("self"))
        assertEquals(4uL, item.effectiveUnreadCount("self"))
        assertTrue(item.effectiveHasUnread("self"))
    }

    @Test
    fun forwardTargetsRequireConfirmedCurrentMembership() {
        val active = item(unreadCount = 0uL, members = listOf("self", "peer"))
        val removed = item(unreadCount = 0uL, members = listOf("peer"))
        val pending = active.copy(group = active.group.copy(pendingConfirmation = true))

        assertTrue(isEligibleForwardTarget(active, "self"))
        assertFalse(isEligibleForwardTarget(removed, "self"))
        assertFalse(isEligibleForwardTarget(pending, "self"))
    }

    // ---- GroupMemberSnapshot.containsAccount --------------------------------

    @Test
    fun containsAccountMatchesCaseInsensitively() {
        val snapshot = GroupMemberSnapshot(listOf(member("ABCDEF")))

        assertTrue(snapshot.containsAccount("abcdef"))
        assertTrue(snapshot.containsAccount("ABCDEF"))
    }

    @Test
    fun containsAccountTrimsTheQueriedId() {
        val snapshot = GroupMemberSnapshot(listOf(member("abcdef")))

        assertTrue(snapshot.containsAccount("  abcdef  "))
    }

    @Test
    fun blankQueryNeverMatchesAnyMember() {
        val snapshot = GroupMemberSnapshot(listOf(member(""), member("abcdef")))

        assertFalse(snapshot.containsAccount(""))
        assertFalse(snapshot.containsAccount("   "))
    }

    // ---- helpers ------------------------------------------------------------

    private fun item(
        unreadCount: ULong,
        members: List<String>?,
        removed: Boolean = false,
        selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
        leaveRequestPending: Boolean = false,
        lifecycleState: GroupLifecycleStateFfi = GroupLifecycleStateFfi.STABLE,
        disbanding: Boolean = false,
    ): ChatListItem =
        ChatListItem(
            group = group("group-a"),
            latest = null,
            otherMemberAccount = null,
            memberCount = members?.size ?: 0,
            memberSnapshot = members?.let { GroupMemberSnapshot(it.map(::member)) },
            projection =
                row("group-a", unreadCount, selfMembership, leaveRequestPending)
                    .copy(lifecycleState = lifecycleState, disbanding = disbanding),
            removed = removed,
        )

    private fun member(accountIdHex: String) =
        AppGroupMemberRecordFfi(
            memberIdHex = accountIdHex,
            account = accountIdHex,
            local = false,
        )

    private fun row(
        groupId: String,
        unreadCount: ULong,
        selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER,
        leaveRequestPending: Boolean = false,
    ) = ChatListRowFfi(
        selfMembership = selfMembership,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = "Group $groupId",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = 0uL,
        updatedAt = 0uL,
        leaveRequestPending = leaveRequestPending,
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

    private fun group(id: String) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = id,
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint-$id",
            name = "",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$id",
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
            defaultBlobEndpoints = listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net")),
        )
}
