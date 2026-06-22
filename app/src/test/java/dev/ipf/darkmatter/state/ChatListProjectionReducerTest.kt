package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Output-contract tests for the chat-list reducer in `Controllers.kt`
 * (`ChatsController` companion #559). Sibling tests (`ChatListSortingTest`,
 * `ChatListTitleTest`) use [chatListItemFromProjection] only as a *fixture
 * builder* to exercise sorting/titling. This file pins the reducer's own
 * emit contract: how a `ChatListRowFfi` projection maps onto the fields of
 * the `ChatListItem` it returns -- the part `ChatsController` relies on every
 * time a `ChatListSubscriptionUpdateFfi` lands.
 *
 * Pure: no `Marmot`, no subscription, no coroutine.
 */
class ChatListProjectionReducerTest {
    // ---- latest synthesis from the row's last-message preview ---------------

    @Test
    fun synthesizesLatestRecordFromLastMessagePreview() {
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "g1",
                    rawTitle = "Marmot Lab",
                    preview =
                        preview(
                            messageId = "m-99",
                            sender = "peer-pubkey",
                            plaintext = "the latest line",
                            kind = 9uL,
                            timelineAt = 42uL,
                        ),
                ),
            )

        val latest = requireNotNull(item.latest) { "a row with a lastMessage must synthesize a latest record" }
        assertEquals("m-99", latest.messageIdHex)
        // The chat-list FFI only carries received previews, so the synthesized
        // record is always tagged "received".
        assertEquals("received", latest.direction)
        assertEquals("g1", latest.groupIdHex)
        assertEquals("peer-pubkey", latest.sender)
        assertEquals("the latest line", latest.plaintext)
        assertEquals(9uL, latest.kind)
        // Both timestamps ride the single preview `timelineAt`.
        assertEquals(42uL, latest.recordedAt)
        assertEquals(42uL, latest.receivedAt)
    }

    @Test
    fun synthesizedLatestCarriesEmptyContentTokensNotTheRowPreviewTokens() {
        // The reducer deliberately leaves the synthesized record's contentTokens
        // empty: the chat-list preview's markdown rides ChatListItem.previewTokens
        // (parsed async by ChatsController), never this record. Parsing here would
        // force an FFI hop inside a pure helper.
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "g1",
                    rawTitle = "Marmot Lab",
                    preview = preview(plaintext = "**bold** preview"),
                ),
            )

        val latest = requireNotNull(item.latest)
        assertTrue(
            "synthesized latest must have empty contentTokens",
            latest.contentTokens.blocks.isEmpty(),
        )
        assertEquals(emptyList<Any>(), latest.tags)
    }

    @Test
    fun latestIsNullWhenTheRowHasNoLastMessage() {
        val item =
            chatListItemFromProjection(
                noMessageRow(groupId = "fresh", rawTitle = "New DM", updatedAt = 7uL),
            )

        assertNull("a row with no lastMessage must not synthesize a latest record", item.latest)
    }

    // ---- group field projection (name / archived / pendingConfirmation) ------

    @Test
    fun rowGroupNameOverridesBaseGroupNameWhenPresent() {
        val item =
            chatListItemFromProjection(
                row(groupId = "g1", rawTitle = "ignored title", groupName = "Renamed In Row"),
                group = group(name = "Stale Base Name"),
            )

        assertEquals("Renamed In Row", item.group.name)
    }

    @Test
    fun blankRowGroupNameFallsBackToBaseGroupName() {
        val item =
            chatListItemFromProjection(
                row(groupId = "g1", rawTitle = "ignored title", groupName = ""),
                group = group(name = "Stable Base Name"),
            )

        assertEquals("Stable Base Name", item.group.name)
    }

    @Test
    fun archivedAndPendingConfirmationProjectFromTheRowOntoTheDisplayGroup() {
        // The row is the live truth for archived/pendingConfirmation; the base
        // group may be stale. The reducer must copy the row's flags onto the
        // display group (sortChatListItems floats pendingConfirmation to the top).
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "g1",
                    rawTitle = "Invite",
                    groupName = "Invite",
                    archived = true,
                    pendingConfirmation = true,
                ),
                group = group(name = "Invite", archived = false, pendingConfirmation = false),
            )

        assertTrue(item.group.archived)
        assertTrue(item.group.pendingConfirmation)
    }

    // ---- members snapshot derivation ----------------------------------------

    @Test
    fun memberSnapshotDrivesOtherMemberAccountAndCount() {
        val me = "me-acc"
        val peer = "peer-acc"
        val members = listOf(member(me, local = true), member(peer, local = false))

        val item =
            chatListItemFromProjection(
                row(groupId = "g1", rawTitle = "00deadbeef".repeat(6)),
                group = group(name = ""),
                activeAccountIdHex = me,
                members = members,
            )

        assertEquals(2, item.memberCount)
        assertEquals(peer, item.otherMemberAccount)
        assertEquals(members, item.memberSnapshot?.members)
    }

    @Test
    fun nullMembersSnapshotLeavesAccountNullAndCountZero() {
        // Without a roster the reducer cannot resolve a peer; the async members
        // fetch in ChatsController fills these in later.
        val item =
            chatListItemFromProjection(
                row(groupId = "g1", rawTitle = "Some Group"),
                group = group(name = "Some Group"),
                activeAccountIdHex = "me-acc",
                members = null,
            )

        assertNull(item.otherMemberAccount)
        assertEquals(0, item.memberCount)
        assertNull(item.memberSnapshot)
    }

    // ---- projection + preview-token passthrough -----------------------------

    @Test
    fun retainsTheSourceRowAsProjectionAndPassesPreviewTokensThrough() {
        val sourceRow = row(groupId = "g1", rawTitle = "Marmot Lab")
        val tokens =
            MarkdownDocumentFfi(
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("hi")))),
            )

        val item =
            chatListItemFromProjection(
                sourceRow,
                previewTokens = tokens,
            )

        // The row is retained verbatim so the derived getters (projectedTitle,
        // unreadCount, latestAt) read straight from the live projection.
        assertEquals(sourceRow, item.projection)
        assertSame(tokens, item.previewTokens)
    }

    @Test
    fun previewTokensDefaultToNullWhenNotSupplied() {
        val item = chatListItemFromProjection(row(groupId = "g1", rawTitle = "Marmot Lab"))
        assertNull(item.previewTokens)
    }

    @Test
    fun emptyGroupFallbackKeepsGroupIdAndRowFlagsWhenNoBaseGroupSupplied() {
        // group = null path: the reducer synthesizes a placeholder group from the
        // row so the item still has a stable id and the row's archived/pending
        // flags survive until the real group record arrives.
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "ghost-group",
                    rawTitle = "Ghost",
                    groupName = "Ghost",
                    archived = true,
                    pendingConfirmation = true,
                ),
            )

        assertEquals("ghost-group", item.id)
        assertEquals("ghost-group", item.group.groupIdHex)
        assertEquals("Ghost", item.group.name)
        assertTrue(item.group.archived)
        assertTrue(item.group.pendingConfirmation)
    }

    // ---- chatRowPreviewMarkdownSource predicate -----------------------------

    @Test
    fun previewMarkdownSourceReturnsPlaintextForALiveMessage() {
        val source =
            chatRowPreviewMarkdownSource(
                row(groupId = "g1", rawTitle = "x", preview = preview(plaintext = "**styled** body")),
            )
        assertEquals("**styled** body", source)
    }

    @Test
    fun previewMarkdownSourceIsNullWhenThereIsNoLastMessage() {
        assertNull(
            chatRowPreviewMarkdownSource(noMessageRow(groupId = "g1", rawTitle = "x", updatedAt = 1uL)),
        )
    }

    @Test
    fun previewMarkdownSourceIsNullForADeletedLastMessage() {
        // Deleted previews show fallback copy, not the (tombstoned) body -- never
        // run them through the markdown parser.
        assertNull(
            chatRowPreviewMarkdownSource(
                row(groupId = "g1", rawTitle = "x", preview = preview(plaintext = "was here", deleted = true)),
            ),
        )
    }

    @Test
    fun previewMarkdownSourceIsNullForABlankPlaintext() {
        assertNull(
            chatRowPreviewMarkdownSource(
                row(groupId = "g1", rawTitle = "x", preview = preview(plaintext = "   ")),
            ),
        )
    }

    // ---- self-removal unread gate (#625) ------------------------------------

    @Test
    fun loadedRosterWithoutActiveAccountZerosTheUnreadBadge() {
        // Removed-by-admin or self-leave: the engine still reports the
        // last-known unread on the row, but the loaded roster proves the
        // active account is gone, so the projected badge must read zero.
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "g1",
                    rawTitle = "Left Group",
                    unreadCount = 7uL,
                    hasUnread = true,
                    firstUnreadMessageIdHex = "m-unread",
                ),
                activeAccountIdHex = "me-acc",
                members = listOf(member("peer-acc", local = false)),
            )

        assertEquals(0uL, item.unreadCount)
        assertEquals(false, item.hasUnread)
        assertNull(item.projection?.firstUnreadMessageIdHex)
    }

    @Test
    fun loadedRosterWithActiveAccountPreservesUnread() {
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "g1",
                    rawTitle = "Joined Group",
                    unreadCount = 7uL,
                    hasUnread = true,
                    firstUnreadMessageIdHex = "m-unread",
                ),
                activeAccountIdHex = "me-acc",
                members = listOf(member("me-acc", local = true), member("peer-acc", local = false)),
            )

        assertEquals(7uL, item.unreadCount)
        assertTrue(item.hasUnread)
        assertEquals("m-unread", item.projection?.firstUnreadMessageIdHex)
    }

    @Test
    fun nullRosterDoesNotZeroUnreadPrematurely() {
        // The roster hasn't been fetched yet; zeroing here would flash an empty
        // badge on every row before the async members fetch lands.
        val item =
            chatListItemFromProjection(
                row(groupId = "g1", rawTitle = "Pending Roster", unreadCount = 3uL, hasUnread = true),
                activeAccountIdHex = "me-acc",
                members = null,
            )

        assertEquals(3uL, item.unreadCount)
        assertTrue(item.hasUnread)
    }

    @Test
    fun blankActiveAccountPreservesUnread() {
        // No known active account: we can't prove removal, so leave the badge.
        val item =
            chatListItemFromProjection(
                row(groupId = "g1", rawTitle = "Unknown Self", unreadCount = 4uL, hasUnread = true),
                activeAccountIdHex = "",
                members = listOf(member("peer-acc", local = false)),
            )

        assertEquals(4uL, item.unreadCount)
        assertTrue(item.hasUnread)
    }

    @Test
    fun activeAccountRemovedFromRoster_predicate() {
        val active = "me-acc"
        val rosterWithMe = listOf(member(active, local = true), member("peer", local = false))
        val rosterWithoutMe = listOf(member("peer", local = false))

        // Loaded roster excluding self → removed.
        assertTrue(activeAccountRemovedFromRoster(rosterWithoutMe, active))
        // Loaded roster including self → not removed.
        assertEquals(false, activeAccountRemovedFromRoster(rosterWithMe, active))
        // Not-yet-fetched roster → not removed (must not zero prematurely).
        assertEquals(false, activeAccountRemovedFromRoster(null, active))
        // No known active account → not removed.
        assertEquals(false, activeAccountRemovedFromRoster(rosterWithoutMe, null))
        assertEquals(false, activeAccountRemovedFromRoster(rosterWithoutMe, "  "))
    }

    @Test
    fun membershipChangedTriggerDropsCachedRosterSoItRefetches() {
        // The stale-positive-cache path: the user viewed the chat list before
        // an admin removed them, so memberCacheByGroup holds the OLD roster
        // (still listing the active account). A MEMBERSHIP_CHANGED row update
        // must evict that entry so schedulePendingMemberFetches() — which skips
        // already-cached groups — re-fetches the current (self-excluded) roster
        // and the gate can zero the badge (issue #625).
        val me = "me-acc"
        val stalePositive = listOf(member(me, local = true), member("peer", local = false))
        val cache = mapOf("g1" to stalePositive, "g2" to stalePositive)

        val after = memberCacheAfterChatRowTrigger(cache, "g1", ChatListUpdateTriggerFfi.MEMBERSHIP_CHANGED)

        assertEquals(false, after.containsKey("g1"))
        // Other groups' rosters are untouched.
        assertEquals(stalePositive, after["g2"])
    }

    @Test
    fun nonMembershipTriggersKeepCachedRoster() {
        // A new message / unread bump / archive flip doesn't change the roster,
        // so re-fetching on every such update would be wasteful churn.
        val me = "me-acc"
        val roster = listOf(member(me, local = true))
        val cache = mapOf("g1" to roster)

        val nonMembershipTriggers =
            listOf(
                ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
                ChatListUpdateTriggerFfi.UNREAD_CHANGED,
                ChatListUpdateTriggerFfi.ARCHIVE_CHANGED,
                ChatListUpdateTriggerFfi.PENDING_CONFIRMATION_CHANGED,
                ChatListUpdateTriggerFfi.NEW_GROUP,
                ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
                ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH,
                ChatListUpdateTriggerFfi.REMOVED,
            )
        for (trigger in nonMembershipTriggers) {
            assertEquals(cache, memberCacheAfterChatRowTrigger(cache, "g1", trigger))
        }
        // A null trigger (snapshot fold, no membership signal) also preserves.
        assertEquals(cache, memberCacheAfterChatRowTrigger(cache, "g1", null))
    }

    @Test
    fun staleCacheInvalidationThenRefetchZerosBadgeEndToEnd() {
        // Wire the two halves together: BEFORE invalidation the stale positive
        // roster keeps the badge; AFTER a MEMBERSHIP_CHANGED eviction the
        // re-fetched self-excluded roster zeros it.
        val me = "me-acc"
        val row =
            row(
                groupId = "g1",
                rawTitle = "Removed By Admin",
                unreadCount = 9uL,
                hasUnread = true,
                firstUnreadMessageIdHex = "m-unread",
            )
        val stalePositive = listOf(member(me, local = true), member("peer", local = false))
        var cache = mapOf("g1" to stalePositive)

        // Stale positive roster → badge survives (the reported bug).
        val before = chatListItemFromProjection(row, activeAccountIdHex = me, members = cache["g1"])
        assertEquals(9uL, before.unreadCount)
        assertTrue(before.hasUnread)

        // Membership commit observed → cache evicted.
        cache = memberCacheAfterChatRowTrigger(cache, "g1", ChatListUpdateTriggerFfi.MEMBERSHIP_CHANGED)
        assertEquals(false, cache.containsKey("g1"))

        // Re-fetch lands with the current self-excluded roster → badge zeroed.
        val refetched = listOf(member("peer", local = false))
        val after = chatListItemFromProjection(row, activeAccountIdHex = me, members = refetched)
        assertEquals(0uL, after.unreadCount)
        assertEquals(false, after.hasUnread)
        assertNull(after.projection?.firstUnreadMessageIdHex)
    }

    // ---- fixtures -----------------------------------------------------------

    private fun preview(
        messageId: String = "m-1",
        sender: String = "peer",
        plaintext: String = "hello",
        kind: ULong = 9uL,
        timelineAt: ULong = 1uL,
        deleted: Boolean = false,
    ) = ChatListMessagePreviewFfi(
        messageIdHex = messageId,
        sender = sender,
        senderDisplayName = null,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
        kind = kind,
        timelineAt = timelineAt,
        deleted = deleted,
    )

    private fun row(
        groupId: String,
        rawTitle: String,
        groupName: String = "",
        archived: Boolean = false,
        pendingConfirmation: Boolean = false,
        preview: ChatListMessagePreviewFfi? = preview(),
        updatedAt: ULong = 1uL,
        unreadCount: ULong = 0uL,
        hasUnread: Boolean = false,
        firstUnreadMessageIdHex: String? = null,
    ) = ChatListRowFfi(
        groupIdHex = groupId,
        archived = archived,
        pendingConfirmation = pendingConfirmation,
        title = rawTitle,
        groupName = groupName,
        avatarUrl = null,
        avatar = null,
        lastMessage = preview,
        unreadCount = unreadCount,
        hasUnread = hasUnread,
        firstUnreadMessageIdHex = firstUnreadMessageIdHex,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        updatedAt = updatedAt,
    )

    private fun noMessageRow(
        groupId: String,
        rawTitle: String,
        updatedAt: ULong,
    ) = row(groupId = groupId, rawTitle = rawTitle, preview = null, updatedAt = updatedAt)

    private fun group(
        name: String,
        groupId: String = "g1",
        archived: Boolean = false,
        pendingConfirmation: Boolean = false,
    ) = AppGroupRecordFfi(
        groupIdHex = groupId,
        endpoint = "endpoint-$groupId",
        name = name,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$groupId",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        encryptedMedia = encryptedMedia(),
        archived = archived,
        pendingConfirmation = pendingConfirmation,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
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
