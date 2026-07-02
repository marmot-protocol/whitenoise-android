package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
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
    ): ChatListItem =
        ChatListItem(
            group = group("group-a"),
            latest = null,
            otherMemberAccount = null,
            memberCount = members?.size ?: 0,
            memberSnapshot = members?.let { GroupMemberSnapshot(it.map(::member)) },
            projection = row("group-a", unreadCount),
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
    ) = ChatListRowFfi(
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
        updatedAt = 0uL,
    )

    private fun group(id: String) =
        AppGroupRecordFfi(
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
            pendingConfirmation = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
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
