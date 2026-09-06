package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListAvatarFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun avatarProjectionOverridesAStaleGroupRecord() {
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "g1",
                    rawTitle = "Image group",
                    avatarUrl = null,
                    imageHashHex = "ab".repeat(32),
                ),
                group =
                    group(name = "Image group").copy(
                        avatarUrl = "https://example.com/stale.jpg",
                        avatarDim = "100x100",
                        avatarThumbhash = "stale",
                        imageHashHex = null,
                    ),
            )

        assertNull(item.group.avatarUrl)
        assertNull(item.group.avatarDim)
        assertNull(item.group.avatarThumbhash)
        assertEquals("ab".repeat(32), item.group.imageHashHex)
    }

    @Test
    fun terminalSelfMembershipInEitherSnapshotVoidsAStalePendingInvite() {
        val snapshotMemberships =
            listOf(
                SelfMembershipFfi.REMOVED to SelfMembershipFfi.MEMBER,
                SelfMembershipFfi.MEMBER to SelfMembershipFfi.REMOVED,
                SelfMembershipFfi.LEFT to SelfMembershipFfi.MEMBER,
                SelfMembershipFfi.MEMBER to SelfMembershipFfi.LEFT,
            )

        snapshotMemberships.forEach { (rowMembership, groupMembership) ->
            val item =
                chatListItemFromProjection(
                    row =
                        row(
                            groupId = "removed-before-open",
                            rawTitle = "Stale invite",
                            pendingConfirmation = true,
                        ).copy(selfMembership = rowMembership),
                    group =
                        group(
                            groupId = "removed-before-open",
                            name = "Stale invite",
                            pendingConfirmation = true,
                        ).copy(selfMembership = groupMembership),
                )

            assertFalse(item.group.pendingConfirmation)
            assertEquals(
                rowMembership.takeIf { it.isNonMember() } ?: groupMembership,
                item.group.selfMembership,
            )
            val seed =
                conversationMembershipSeed(
                    item.group,
                    initialMemberSnapshot = null,
                    activeAccountIdHex = "self",
                )
            assertTrue(seed.membersVerified)
            assertFalse(seed.seededSelfMember)
        }
    }

    /** Terminal membership remains sticky across later same-generation projections. */
    @Test
    fun postOpenTerminalUpdatesVoidTheInviteAndCannotBeOverwrittenByStaleSnapshots() {
        listOf(SelfMembershipFfi.REMOVED, SelfMembershipFfi.LEFT).forEach { terminalMembership ->
            val staleInvite = group(name = "Stale invite", pendingConfirmation = true)
            val afterTerminalUpdate =
                reconcileTerminalSelfMembership(
                    update =
                        staleInvite.copy(
                            selfMembership = terminalMembership,
                            pendingConfirmation = true,
                        ),
                    previous = staleInvite,
                )

            assertEquals(terminalMembership, afterTerminalUpdate.selfMembership)
            assertFalse(afterTerminalUpdate.pendingConfirmation)

            val afterLaterStaleUpdate =
                reconcileTerminalSelfMembership(
                    update =
                        afterTerminalUpdate.copy(
                            selfMembership = SelfMembershipFfi.MEMBER,
                            pendingConfirmation = true,
                        ),
                    previous = afterTerminalUpdate,
                )

            assertEquals(terminalMembership, afterLaterStaleUpdate.selfMembership)
            assertFalse(afterLaterStaleUpdate.pendingConfirmation)
        }
    }

    /** A different canonical Welcome is the sole membership-terminal escape hatch. */
    @Test
    fun distinctWelcomeGenerationCanSurfaceAGenuineReinviteAfterRemoval() {
        val removed =
            group(name = "Removed", pendingConfirmation = false).copy(
                selfMembership = SelfMembershipFfi.REMOVED,
                viaWelcomeMessageIdHex = "old-welcome",
            )

        val sameWelcomeReplay =
            reconcileTerminalSelfMembership(
                update =
                    removed.copy(
                        selfMembership = SelfMembershipFfi.MEMBER,
                        pendingConfirmation = true,
                    ),
                previous = removed,
            )
        val distinctWelcome =
            reconcileTerminalSelfMembership(
                update =
                    removed.copy(
                        selfMembership = SelfMembershipFfi.MEMBER,
                        pendingConfirmation = true,
                        viaWelcomeMessageIdHex = "new-welcome",
                    ),
                previous = removed,
            )

        assertEquals(SelfMembershipFfi.REMOVED, sameWelcomeReplay.selfMembership)
        assertFalse(sameWelcomeReplay.pendingConfirmation)
        assertEquals(SelfMembershipFfi.MEMBER, distinctWelcome.selfMembership)
        assertTrue(distinctWelcome.pendingConfirmation)
    }

    /** Missing prior generation identity cannot prove that a later Welcome is new. */
    @Test
    fun nullOrBlankTerminalWelcomeKeepsANonblankReplayFailClosed() {
        listOf(null, "").forEach { unknownPriorWelcome ->
            val terminal =
                group(name = "Removed", pendingConfirmation = false).copy(
                    selfMembership = SelfMembershipFfi.REMOVED,
                    viaWelcomeMessageIdHex = unknownPriorWelcome,
                )
            val replay =
                reconcileTerminalSelfMembership(
                    update =
                        terminal.copy(
                            selfMembership = SelfMembershipFfi.MEMBER,
                            pendingConfirmation = true,
                            viaWelcomeMessageIdHex = "unproven-welcome",
                        ),
                    previous = terminal,
                )

            assertEquals(SelfMembershipFfi.REMOVED, replay.selfMembership)
            assertFalse(replay.pendingConfirmation)
        }
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
    fun reconciledGroupMembershipSuppressesUnreadWithAStaleRowAndRoster() {
        val me = "me-acc"
        val item =
            chatListItemFromProjection(
                row =
                    row(
                        groupId = "g1",
                        rawTitle = "Marmot Lab",
                        unreadCount = 7uL,
                        hasUnread = true,
                    ),
                group = group(name = "Marmot Lab").copy(selfMembership = SelfMembershipFfi.REMOVED),
                activeAccountIdHex = me,
                members = listOf(member(me, local = true), member("peer-acc", local = false)),
            )

        assertEquals(SelfMembershipFfi.REMOVED, item.group.selfMembership)
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

    // ---- projection + preview-token passthrough -----------------------------

    @Test
    fun retainsTheSourceRowAsProjectionAndPassesPreviewTokensThrough() {
        val sourceRow = row(groupId = "g1", rawTitle = "Marmot Lab")
        val tokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("hi")))),
                blankLinesBefore = ByteArray(0),
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

    /** Verifies an eligible body uses the exact Markdown document supplied by the MDK projection. */
    @Test
    fun projectedMarkdownTokensOwnTheFirstEligibleBodyProjection() {
        val tokens = markdown("rendered")
        val sourceRow =
            row(
                groupId = "g1",
                rawTitle = "Marmot Lab",
                preview = preview(plaintext = "**rendered**", contentTokens = tokens),
            )

        val item = chatListItemFromProjection(sourceRow)

        assertSame(tokens, item.previewTokens)
    }

    /** Verifies derived preview kinds cannot style their wrapper payload as a visible message body. */
    @Test
    fun projectedMarkdownTokensDoNotOverrideAFallbackPreviewKind() {
        val sourceRow =
            row(
                groupId = "g1",
                rawTitle = "Marmot Lab",
                preview = preview(plaintext = "edit wrapper", kind = 1009uL, contentTokens = markdown("wrong body")),
            )

        val item = chatListItemFromProjection(sourceRow)

        assertNull(item.previewTokens)
    }

    /** Verifies current projected tokens take precedence over stale exact-text parser cache entries. */
    @Test
    fun projectedMarkdownWinsOverAStaleExactTextCacheEntry() {
        val projected = markdown("current")
        val cached = markdown("stale")
        val sourceRow =
            row(
                groupId = "g1",
                rawTitle = "Marmot Lab",
                preview = preview(plaintext = "same source", contentTokens = projected),
            )

        assertSame(projected, chatRowPreviewTokens(sourceRow, mapOf("same source" to cached)))
        assertNull(chatRowPreviewMarkdownFallbackSource(sourceRow))
    }

    /** Verifies legacy empty projections use the cache and remain eligible for parser fallback. */
    @Test
    fun emptyProjectedMarkdownUsesTheExactTextParserFallback() {
        val cached = markdown("fallback")
        val sourceRow = row(groupId = "g1", rawTitle = "Marmot Lab", preview = preview(plaintext = "source"))

        assertSame(cached, chatRowPreviewTokens(sourceRow, mapOf("source" to cached)))
        assertEquals("source", chatRowPreviewMarkdownFallbackSource(sourceRow))
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

    /** Creates a preview whose Markdown payload can be varied independently of its plaintext. */
    private fun preview(
        messageId: String = "m-1",
        sender: String = "peer",
        plaintext: String = "hello",
        kind: ULong = 9uL,
        timelineAt: ULong = 1uL,
        deleted: Boolean = false,
        contentTokens: MarkdownDocumentFfi = markdown(),
    ) = ChatListMessagePreviewFfi(
        messageIdHex = messageId,
        sender = sender,
        senderDisplayName = null,
        plaintext = plaintext,
        contentTokens = contentTokens,
        kind = kind,
        timelineAt = timelineAt,
        deleted = deleted,
        attachmentKind = null,
        attachmentCount = 0u,
        deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
    )

    /** Builds either an empty legacy document or one projected paragraph. */
    private fun markdown(text: String? = null) =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = text?.let { listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(it)))) }.orEmpty(),
            blankLinesBefore = ByteArray(0),
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
        avatarUrl: String? = null,
        imageHashHex: String? = null,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = archived,
        pendingConfirmation = pendingConfirmation,
        title = rawTitle,
        groupName = groupName,
        avatarUrl = avatarUrl,
        avatar =
            imageHashHex?.let {
                ChatListAvatarFfi(
                    imageHashHex = it,
                    imageKeyHex = "redacted-test-key",
                    imageNonceHex = "redacted-test-nonce",
                    imageUploadKeyHex = "redacted-test-upload-key",
                    mediaType = "image/jpeg",
                )
            },
        lastMessage = preview,
        unreadCount = unreadCount,
        hasUnread = hasUnread,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = 0uL,
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
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = groupId,
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint-$groupId",
        name = name,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$groupId",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia = encryptedMedia(),
        archived = archived,
        pendingConfirmation = pendingConfirmation,
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
}
