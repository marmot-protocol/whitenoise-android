package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the five title cases the chat-list rows route through:
 *  1. Named group → the group's name.
 *  2. Unnamed 2-member group → the other member's resolved title.
 *  3. Unnamed 3+ member group → "Group of N people" copy.
 *  4. Pending invite → "Invite from <welcomer>" via welcomer fallback.
 *  5. A raw-hex projected title from the FFI does NOT override the
 *     local member-driven projection for an unnamed group.
 *
 * The chat-list helper itself is private to the UI surface, so these
 * tests exercise `GroupProjector.displayTitle` with the same inputs
 * `chatListItemFromProjection` feeds it. Case 5 also checks the
 * projection helper populates `otherMemberAccount` + `memberCount`
 * from a passed-in members snapshot.
 */
class ChatListTitleTest {
    private val copy =
        GroupTitleCopy(
            inviteFromFormat = "Invite from %1\$s",
            groupOfPeopleFormat = "Group of %1\$d people",
        )

    @Test
    fun namedGroupTitleWinsOverEverything() {
        val title =
            GroupProjector.displayTitle(
                group = group(name = "Marmot Lab"),
                otherMemberAccount = "peer-acc",
                memberCount = 7,
                memberTitle = { "Should Not Be Used" },
                copy = copy,
            )
        assertEquals("Marmot Lab", title)
    }

    @Test
    fun unnamedTwoMemberGroupShowsOtherMemberTitle() {
        val title =
            GroupProjector.displayTitle(
                group = group(name = ""),
                otherMemberAccount = "peer-acc",
                memberCount = 2,
                memberTitle = { id -> if (id == "peer-acc") "Alice" else "you" },
                copy = copy,
            )
        assertEquals("Alice", title)
    }

    @Test
    fun unnamedManyMemberGroupShowsGroupOfPeopleCopy() {
        val title =
            GroupProjector.displayTitle(
                group = group(name = ""),
                otherMemberAccount = "peer-acc",
                memberCount = 5,
                memberTitle = { "Should Not Be Used" },
                copy = copy,
            )
        assertEquals("Group of 5 people", title)
    }

    @Test
    fun whitespaceOnlyGroupNameRoutesThroughUnnamedPath() {
        // Locks the `isNotBlank()` contract: a name of pure whitespace
        // must not satisfy the named-group branch. Otherwise the title
        // would render as a blank string instead of the peer name.
        val title =
            GroupProjector.displayTitle(
                group = group(name = "   "),
                otherMemberAccount = "peer-acc",
                memberCount = 2,
                memberTitle = { id -> if (id == "peer-acc") "Alice" else "you" },
                copy = copy,
            )
        assertEquals("Alice", title)
    }

    @Test
    fun pendingInviteUsesWelcomerFallback() {
        val title =
            GroupProjector.displayTitle(
                group = group(name = "", pendingConfirmation = true, welcomer = "welcomer-acc"),
                otherMemberAccount = null,
                memberCount = 0,
                memberTitle = { id -> if (id == "welcomer-acc") "Bob" else "?" },
                copy = copy,
            )
        assertEquals("Invite from Bob", title)
    }

    @Test
    fun chatListProjectionTrustsLocalMembersOverRawHexTitle() {
        // Even when the FFI row's `title` is the raw group hex (the
        // exact failure mode the chat-list bug landed on), the
        // projection helper, fed a real members snapshot, must
        // populate `otherMemberAccount` + `memberCount = 2`. That's
        // what lets the local display projector return the peer's
        // title instead of the hex.
        val me = "me-acc"
        val peer = "peer-acc"
        val members =
            listOf(
                member(me, local = true),
                member(peer, local = false),
            )
        val hexLikeTitle = "00deadbeef".repeat(6)
        val item =
            chatListItemFromProjection(
                row =
                    row(
                        groupId = "test-group",
                        rawTitle = hexLikeTitle,
                    ),
                group = group(name = ""),
                activeAccountIdHex = me,
                members = members,
            )

        assertEquals(2, item.memberCount)
        assertEquals(peer, item.otherMemberAccount)

        val title =
            GroupProjector.displayTitle(
                group = item.group,
                otherMemberAccount = item.otherMemberAccount,
                memberCount = item.memberCount,
                memberTitle = { id -> if (id == peer) "Alice" else "Self" },
                copy = copy,
            )
        assertEquals("Alice", title)
    }

    @Test
    fun sharedGroupsRequireLocalAndTargetMembersInSnapshot() {
        val me = "me-acc"
        val alice = "alice-acc"
        val bob = "bob-acc"
        val shared = chatItem("shared", name = "Shared", members = listOf(member(me, true), member(alice, false)))
        val missingTarget = chatItem("missing-target", name = "No Alice", members = listOf(member(me, true), member(bob, false)))
        val missingSelf = chatItem("missing-self", name = "No Me", members = listOf(member(alice, false), member(bob, false)))
        val noSnapshot = chatItem("no-snapshot", name = "Unknown", members = null)

        val matches = sharedChatListItemsWith(listOf(shared, missingTarget, missingSelf, noSnapshot), "ALICE-ACC", "ME-ACC")

        assertEquals(listOf("shared"), matches.map { it.group.groupIdHex })
    }

    // --- fixtures ---

    private fun group(
        name: String,
        pendingConfirmation: Boolean = false,
        welcomer: String? = null,
        groupId: String = "test-group",
    ) = AppGroupRecordFfi(
        groupIdHex = groupId,
        endpoint = "endpoint",
        name = name,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-test-group",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        encryptedMedia = encryptedMedia(),
        archived = false,
        pendingConfirmation = pendingConfirmation,
        welcomerAccountIdHex = welcomer,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
    )

    private fun chatItem(
        groupId: String,
        name: String,
        members: List<AppGroupMemberRecordFfi>?,
    ): ChatListItem =
        chatListItemFromProjection(
            row = row(groupId = groupId, rawTitle = name),
            group = group(name = name, groupId = groupId),
            activeAccountIdHex = "me-acc",
            members = members,
        )

    private fun row(
        groupId: String,
        rawTitle: String,
    ) = ChatListRowFfi(
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = rawTitle,
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage =
            ChatListMessagePreviewFfi(
                messageIdHex = "last-message",
                sender = "peer-acc",
                senderDisplayName = null,
                plaintext = "hello",
                contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
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

    private fun member(
        accountIdHex: String,
        local: Boolean,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = accountIdHex,
        account = if (local) accountIdHex else null,
        local = local,
    )

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
