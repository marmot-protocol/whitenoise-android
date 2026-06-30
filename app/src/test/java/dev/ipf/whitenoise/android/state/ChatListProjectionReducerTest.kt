package dev.ipf.whitenoise.android.state

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

    // ---- removed-group marker (drives the left-state row) -------------------

    @Test
    fun removedFlagFlipsRemovedFromGroupEvenWhileTheRosterStillContainsSelf() {
        // markGroupLeft (and the chat-list leaveGroup) flip a row to its left
        // state by adding the group to ChatsController.removedGroupIds, which
        // surfaces here as removed = true. That marker must win even when the
        // cached roster still lists self -- the engine pushes no chat-list
        // update for a self-leave, so the roster can lag (issue #767).
        val me = "me-acc"
        val item =
            chatListItemFromProjection(
                row(groupId = "g1", rawTitle = "Marmot Lab", unreadCount = 7uL, hasUnread = true),
                group = group(name = "Marmot Lab"),
                activeAccountIdHex = me,
                members = listOf(member(me, local = true), member("peer-acc", local = false)),
                removed = true,
            )

        assertTrue(item.removedFromGroup(me))
        assertEquals(0uL, item.effectiveUnreadCount(me))
    }

    @Test
    fun withoutRemovedFlagAStaleSelfIncludingRosterStaysActive() {
        // Guards the other half of the contract: absent the removed marker, a
        // roster that still includes self reads as active, so the Details-path
        // fix genuinely depends on markGroupLeft setting removed.
        val me = "me-acc"
        val item =
            chatListItemFromProjection(
                row(groupId = "g1", rawTitle = "Marmot Lab"),
                group = group(name = "Marmot Lab"),
                activeAccountIdHex = me,
                members = listOf(member(me, local = true), member("peer-acc", local = false)),
                removed = false,
            )

        assertEquals(false, item.removedFromGroup(me))
    }

    @Test
    fun membershipChatListUpdatesForceMemberSnapshotRefresh() {
        assertTrue(shouldRefreshMembersForChatListUpdate(ChatListUpdateTriggerFfi.MEMBERSHIP_CHANGED))
        assertTrue(shouldRefreshMembersForChatListUpdate(ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH))
        assertEquals(false, shouldRefreshMembersForChatListUpdate(ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE))
    }

    // ---- projection + preview-token passthrough -----------------------------

    @Test
    fun retainsTheSourceRowAsProjectionAndPassesPreviewTokensThrough() {
        val sourceRow = row(groupId = "g1", rawTitle = "Marmot Lab")
        val tokens =
            MarkdownDocumentFfi(
                truncated = false,
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
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
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
    ) = ChatListRowFfi(
        unreadMentionCount = 0uL,
        unreadMention = false,
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
        firstUnreadMessageIdHex = null,
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
        disappearingMessageSecs = 0uL,
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
