package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
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
    fun hostileNamedTitleIsSanitizedInTheSortKey() {
        // #980: the sort key must track the *sanitized* title the row renders,
        // so a bidi-override-laden name sorts by its visible form.
        val item =
            item(
                groupId = "aaaa",
                groupName = "\u202EEvil\u202C Group",
            )

        assertEquals("evil group", chatListItemSortKey(item))
    }

    @Test
    fun zeroWidthOnlyGroupNameRoutesThroughTheUnnamedPath() {
        // A name that sanitization strips entirely renders via the unnamed
        // projection, so the key must come from the peer account too.
        val item =
            item(
                groupId = "aaaa",
                groupName = "\u200B\u200E\uFEFF",
                otherMemberAccount = "peer",
            )

        assertEquals("peer", chatListItemSortKey(item))
    }

    @Test
    fun zeroWidthOnlyProjectedTitleFallsBackToSanitizedRawName() {
        // A stale projected title that sanitizes away must not demote the row
        // to the unnamed path when the raw group name is still displayable —
        // chatListItemDisplayTitle recovers from the raw name, and the sort
        // key must track what the row renders.
        val item =
            item(
                groupId = "aaaa",
                groupName = "Real Name",
                projectedTitle = "\u200B\u200E\uFEFF",
            )

        assertEquals("real name", chatListItemSortKey(item))
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

    @Test
    fun allPrunedUnreadChatUsesDurableActivityTimestamp() {
        val item =
            item(
                groupId = "pruned-unread",
                groupName = "Pruned",
                projectedActivitySortAt = 120uL,
                projectedUpdatedAt = 900uL,
            )

        assertEquals(120uL, item.latestAt)
    }

    @Test
    fun emptyConversationFallsBackToItsCreationTimestamp() {
        val item =
            item(
                groupId = "fresh-empty",
                groupName = "Fresh",
                projectedActivitySortAt = 0uL,
                projectedConversationCreatedAt = 250uL,
                projectedUpdatedAt = 900uL,
            )

        assertEquals(250uL, item.latestAt)
    }

    @Test
    fun projectionRebuildTimeIsTheFinalProjectedFallback() {
        val item =
            item(
                groupId = "legacy-row",
                groupName = "Legacy",
                projectedActivitySortAt = 0uL,
                projectedUpdatedAt = 900uL,
            )

        assertEquals(900uL, item.latestAt)
    }

    // ---- helpers ------------------------------------------------------------

    private fun item(
        groupId: String,
        groupName: String,
        projectedTitle: String? = null,
        projectedActivitySortAt: ULong? = null,
        projectedConversationCreatedAt: ULong = 0uL,
        projectedUpdatedAt: ULong = 0uL,
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
            projection =
                if (projectedTitle != null || projectedActivitySortAt != null) {
                    row(
                        groupId = groupId,
                        groupName = groupName,
                        title = projectedTitle.orEmpty(),
                        activitySortAt = projectedActivitySortAt ?: 0uL,
                        conversationCreatedAt = projectedConversationCreatedAt,
                        updatedAt = projectedUpdatedAt,
                    )
                } else {
                    null
                },
        )

    private fun row(
        groupId: String,
        groupName: String,
        title: String,
        activitySortAt: ULong = 0uL,
        conversationCreatedAt: ULong = 0uL,
        updatedAt: ULong = 0uL,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
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
        conversationCreatedAt = conversationCreatedAt,
        activitySortAt = activitySortAt,
        updatedAt = updatedAt,
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

    private fun group(
        id: String,
        name: String,
    ) = AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = id,
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint-$id",
        name = name,
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

    private fun message(
        groupId: String,
        recordedAt: ULong,
    ) = dev.ipf.marmotkit.AppMessageRecordFfi(
        messageIdHex = "message-$groupId",
        direction = "received",
        groupIdHex = groupId,
        sender = "sender",
        plaintext = "hello",
        contentTokens =
            dev.ipf.marmotkit.MarkdownDocumentFfi(
                truncated = false,
                blocks = emptyList(),
                blankLinesBefore = ByteArray(0),
            ),
        kind = 9uL,
        tags = emptyList(),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = recordedAt,
        receivedAt = recordedAt,
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
