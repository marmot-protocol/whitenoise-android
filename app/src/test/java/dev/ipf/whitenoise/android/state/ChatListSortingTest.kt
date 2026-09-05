package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListSortingTest {
    @Test
    fun pinnedChatsRideAboveRecencyInEngineManualOrder() {
        val pinnedSecond = item("pinned-second", latestAt = 9_000uL, pinned = true, pinnedPosition = 1u)
        val pinnedFirst = item("pinned-first", latestAt = 100uL, pinned = true, pinnedPosition = 0u)
        val recent = item("recent", latestAt = 50_000uL)
        val invited = item("invited", latestAt = 10uL, pending = true)

        val sorted = sortChatListItems(listOf(recent, pinnedSecond, pinnedFirst, invited))

        // Pending invites stay on top, the pinned block follows in the
        // engine's manual order regardless of recency, unpinned rows keep
        // the recency chain.
        assertEquals(
            listOf("invited", "pinned-first", "pinned-second", "recent"),
            sorted.map { it.id },
        )
    }

    @Test
    fun chatsWithoutMessagesSortAfterChatsWithMessages() {
        val withLatest = item("with-latest", latestAt = 25uL)
        val withoutLatest = item("without-latest", latestAt = null)

        val sorted = sortChatListItems(listOf(withoutLatest, withLatest))

        assertEquals(listOf("with-latest", "without-latest"), sorted.map { it.id })
    }

    @Test
    fun chatsWithoutMessagesCanSortBesideUnsignedLongMessageTimes() {
        val sorted =
            sortChatListItems(
                listOf(
                    item("no-message", latestAt = null),
                    item("newer", latestAt = ULong.MAX_VALUE),
                    item("older", latestAt = 1uL),
                ),
            )

        assertEquals(listOf("newer", "older", "no-message"), sorted.map { it.id })
    }

    @Test
    fun aNewerDraftRaisesAChatAboveOneWithAMoreRecentMessage() {
        val draftedIn = item("drafted-in", latestAt = 10uL)
        val recentMessage = item("recent-message", latestAt = 50uL)

        val sorted =
            sortChatListItems(listOf(recentMessage, draftedIn)) { item ->
                if (item.id == "drafted-in") 90uL else null
            }

        assertEquals(listOf("drafted-in", "recent-message"), sorted.map { it.id })
    }

    @Test
    fun authoritativeDraftEditMovesOnlyTheEditedChat() {
        val persistence =
            object : DraftPersistence {
                override fun read(): Map<String, String> = emptyMap()

                override fun write(
                    key: String,
                    value: String?,
                ) = Unit
            }
        val drafts = DraftStore(persistence) { 20L }
        val edited = item("edited", latestAt = 10uL)
        val other = item("other", latestAt = 50uL)
        drafts.set("account", edited.id, TextFieldValue("draft"))

        assertEquals(
            listOf("other", "edited"),
            sortChatListItems(listOf(edited, other)) { drafts.draftedAtSecondsFor("account", it.id) }
                .map { it.id },
        )

        drafts.applyAuthoritativeTimestamp("account", edited.id, draftedAtMs = 90_000)

        assertEquals(
            listOf("edited", "other"),
            sortChatListItems(listOf(edited, other)) { drafts.draftedAtSecondsFor("account", it.id) }
                .map { it.id },
        )
    }

    @Test
    fun aStaleDraftDoesNotOutrankAFresherMessage() {
        // The draft is older than the chat's own last message, so it must not
        // change the order — an incoming message stays ahead of an old draft.
        val chatWithOldDraft = item("old-draft", latestAt = 80uL)
        val freshMessage = item("fresh", latestAt = 60uL)

        val sorted =
            sortChatListItems(listOf(chatWithOldDraft, freshMessage)) { item ->
                if (item.id == "old-draft") 20uL else null
            }

        assertEquals(listOf("old-draft", "fresh"), sorted.map { it.id })
    }

    @Test
    fun aDraftInAMessagelessChatRaisesItAboveOlderConversations() {
        val draftedNewChat = item("new-drafted", latestAt = null)
        val olderChat = item("older", latestAt = 40uL)

        val sorted =
            sortChatListItems(listOf(olderChat, draftedNewChat)) { item ->
                if (item.id == "new-drafted") 99uL else null
            }

        assertEquals(listOf("new-drafted", "older"), sorted.map { it.id })
    }

    @Test
    fun noActivityTieFallsBackToStableTitleInsteadOfMaterializationSequence() {
        val alpha =
            item("alpha", latestAt = null, activitySequence = 1uL)
                .copy(group = group("alpha").copy(name = "Alpha"))
        val zulu =
            item("zulu", latestAt = null, activitySequence = 2uL)
                .copy(group = group("zulu").copy(name = "Zulu"))

        val sorted = sortChatListItems(listOf(zulu, alpha))

        assertEquals(listOf("alpha", "zulu"), sorted.map { it.id })
    }

    @Test
    fun sameSecondDraftTieFallsBackToTitleInsteadOfMessageActivitySequence() {
        val alpha =
            item("alpha", latestAt = 10uL, activitySequence = 1uL)
                .copy(group = group("alpha").copy(name = "Alpha"))
        val zulu =
            item("zulu", latestAt = 20uL, activitySequence = 2uL)
                .copy(group = group("zulu").copy(name = "Zulu"))

        val sorted = sortChatListItems(listOf(zulu, alpha)) { 90uL }

        assertEquals(listOf("alpha", "zulu"), sorted.map { it.id })
    }

    @Test
    fun draftAndMessageRecencyTieFallsBackToTitleInsteadOfMessageActivitySequence() {
        val alpha =
            item("alpha", latestAt = 10uL, activitySequence = 1uL)
                .copy(group = group("alpha").copy(name = "Alpha"))
        val zulu =
            item("zulu", latestAt = 90uL, activitySequence = 2uL)
                .copy(group = group("zulu").copy(name = "Zulu"))

        val sorted = sortChatListItems(listOf(zulu, alpha)) { item -> if (item.id == "alpha") 90uL else null }

        assertEquals(listOf("alpha", "zulu"), sorted.map { it.id })
    }

    @Test
    fun pendingInvitesSortBeforeExistingChats() {
        val sorted =
            sortChatListItems(
                listOf(
                    item("active-chat", latestAt = 50uL),
                    item("pending-invite", latestAt = null, pending = true),
                ),
            )

        assertEquals(listOf("pending-invite", "active-chat"), sorted.map { it.id })
    }

    @Test
    fun unnamedGroupsSortByPeerAccountNotGroupHex() {
        // Two unnamed groups, identical latestAt → tie-break falls through to
        // the title key. The raw group hex must NOT be the key: a peer
        // account is always preferred when present so the sort tracks the
        // display title rather than the cosmetic group id ordering.
        val zeebra =
            ChatListItem(
                group = group("ffff-comes-first-by-hex"),
                latest = message(groupId = "ffff-comes-first-by-hex", recordedAt = 100uL),
                otherMemberAccount = "zeebra-account",
                memberCount = 2,
                memberSnapshot = null,
            )
        val alpha =
            ChatListItem(
                group = group("0000-comes-last-by-hex"),
                latest = message(groupId = "0000-comes-last-by-hex", recordedAt = 100uL),
                otherMemberAccount = "alpha-account",
                memberCount = 2,
                memberSnapshot = null,
            )

        val sorted = sortChatListItems(listOf(zeebra, alpha))

        assertEquals(
            listOf("0000-comes-last-by-hex", "ffff-comes-first-by-hex"),
            sorted.map { it.id },
        )
    }

    @Test
    fun projectedChatListRowCarriesTitlePreviewTimestampAndUnreadState() {
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "group-a",
                    title = "Marmot Lab",
                    preview = "projected latest",
                    latestAt = 20uL,
                    unreadCount = 3uL,
                ),
            )

        assertEquals("Marmot Lab", item.projectedTitle)
        assertEquals("projected latest", item.projectedPreviewText(empty = "empty"))
        assertEquals(20uL, item.latestAt)
        assertEquals(3uL, item.unreadCount)
        assertTrue(item.hasUnread)
    }

    @Test
    fun freshlyCreatedChatWithNoMessagesSortsAboveOlderMessagedChats() {
        // A just-created DM/group has a projection (with `updatedAt` ≈ now) but
        // no last message yet. Its `latestAt` must fall back to `updatedAt` so
        // it sorts to the TOP of the chat list, matching the "most recent
        // activity" ordering, rather than collapsing to 0uL and landing at the
        // bottom (issue #321).
        val olderMessaged =
            chatListItemFromProjection(
                row(
                    groupId = "older-with-message",
                    title = "Old Chat",
                    preview = "an old message",
                    latestAt = 100uL,
                    unreadCount = 0uL,
                ),
            )
        val freshlyCreated =
            chatListItemFromProjection(
                noMessageRow(
                    groupId = "fresh-no-message",
                    title = "New DM",
                    updatedAt = 200uL,
                ),
            )

        assertEquals(200uL, freshlyCreated.latestAt)
        val sorted = sortChatListItems(listOf(olderMessaged, freshlyCreated))
        assertEquals(listOf("fresh-no-message", "older-with-message"), sorted.map { it.id })
    }

    @Test
    fun lastMessageTimestampStillWinsOverProjectionUpdatedAt() {
        // The `updatedAt` fallback must NOT override an existing chat's
        // last-message ordering: a chat with a message keeps sorting on the
        // message's timeline timestamp even when its projection `updatedAt`
        // (bumped by e.g. a read-state or avatar change) is more recent.
        val item =
            chatListItemFromProjection(
                row(
                    groupId = "messaged",
                    title = "Messaged",
                    preview = "hello",
                    latestAt = 50uL,
                    unreadCount = 0uL,
                ).copy(updatedAt = 9999uL),
            )

        assertEquals(50uL, item.latestAt)
    }

    @Test
    fun allExpiredChatSortsByLastReadNotThePruneBumpedUpdatedAt() {
        // A disappearing-messages chat whose messages have all expired has no
        // last message, and the prune rebuilds its projection row with
        // `updatedAt` ≈ now. It must sort by its retained last-read timeline
        // position — where its last visible message sat — rather than jumping
        // to the top on the prune-bumped `updatedAt` (issue #849).
        val activeChat =
            chatListItemFromProjection(
                row(
                    groupId = "active",
                    title = "Active",
                    preview = "recent",
                    latestAt = 100uL,
                    unreadCount = 0uL,
                ),
            )
        val allExpired =
            chatListItemFromProjection(
                noMessageRow(
                    groupId = "all-expired",
                    title = "Expired",
                    updatedAt = 999uL,
                    lastReadTimelineAt = 10uL,
                ),
            )

        // latestAt keys on the read position (10), not the prune-bumped 999.
        assertEquals(10uL, allExpired.latestAt)
        val sorted = sortChatListItems(listOf(allExpired, activeChat))
        assertEquals(listOf("active", "all-expired"), sorted.map { it.id })
    }

    @Test
    fun durableActivityTimestampOutranksAFresherMessagePreview() {
        val pinnedByActivity =
            chatListItemFromProjection(
                row(
                    groupId = "durable",
                    title = "Durable",
                    preview = "old message",
                    latestAt = 100uL,
                    unreadCount = 0uL,
                    activitySortAt = 300uL,
                ),
            )
        val fresherMessage =
            chatListItemFromProjection(
                row(
                    groupId = "fresher",
                    title = "Fresher",
                    preview = "new message",
                    latestAt = 200uL,
                    unreadCount = 0uL,
                ),
            )

        assertEquals(300uL, pinnedByActivity.latestAt)
        val sorted = sortChatListItems(listOf(fresherMessage, pinnedByActivity))
        assertEquals(listOf("durable", "fresher"), sorted.map { it.id })
    }

    @Test
    fun emptyHistoryChatSortsByConversationCreationOverProjectionRebuild() {
        val messaged =
            chatListItemFromProjection(
                row(groupId = "messaged", title = "Messaged", preview = "hi", latestAt = 200uL, unreadCount = 0uL),
            )
        val emptyHistory =
            chatListItemFromProjection(
                noMessageRow(groupId = "empty", title = "Empty", updatedAt = 50uL, conversationCreatedAt = 250uL),
            )

        assertEquals(250uL, emptyHistory.latestAt)
        val sorted = sortChatListItems(listOf(messaged, emptyHistory))
        assertEquals(listOf("empty", "messaged"), sorted.map { it.id })
    }

    @Test
    fun rollbackOptimisticChatListPreviewRestoresPreviousRowWhenTempMessageStillOwnsPreview() {
        val previous = row(groupId = "chat", title = "Chat", preview = "real latest", latestAt = 10uL, unreadCount = 0uL)
        val optimistic =
            previous.copy(
                lastMessage = preview(messageId = "temp-1", plaintext = "failed draft", latestAt = 20uL),
                updatedAt = 20uL,
            )

        assertEquals(
            previous,
            rollbackOptimisticChatListPreview(
                current = optimistic,
                previous = previous,
                optimisticMessageIdHex = "temp-1",
            ),
        )
    }

    @Test
    fun rollbackOptimisticChatListPreviewKeepsCurrentRowWhenAuthoritativePreviewArrived() {
        val previous = row(groupId = "chat", title = "Chat", preview = "real latest", latestAt = 10uL, unreadCount = 0uL)
        val authoritative =
            previous.copy(
                lastMessage = preview(messageId = "real-2", plaintext = "new real latest", latestAt = 30uL),
                updatedAt = 30uL,
            )

        assertEquals(
            authoritative,
            rollbackOptimisticChatListPreview(
                current = authoritative,
                previous = previous,
                optimisticMessageIdHex = "temp-1",
            ),
        )
    }

    private fun item(
        id: String,
        latestAt: ULong?,
        pending: Boolean = false,
        pinned: Boolean = false,
        pinnedPosition: UInt? = null,
        activitySequence: ULong = 0uL,
    ): ChatListItem =
        ChatListItem(
            group = group(id, pending = pending),
            latest = latestAt?.let { message(groupId = id, recordedAt = it) },
            otherMemberAccount = null,
            memberCount = 0,
            memberSnapshot = null,
            projection =
                if (pinned) {
                    row(
                        groupId = id,
                        title = id,
                        preview = "",
                        latestAt = latestAt ?: 0uL,
                        unreadCount = 0uL,
                    ).copy(pinned = true, pinnedPosition = pinnedPosition)
                } else {
                    null
                },
            activitySequence = activitySequence,
        )

    private fun row(
        groupId: String,
        title: String,
        preview: String,
        latestAt: ULong,
        unreadCount: ULong,
        activitySortAt: ULong = 0uL,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = title,
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage =
            ChatListMessagePreviewFfi(
                messageIdHex = "message-$groupId",
                sender = "sender",
                senderDisplayName = "Sender",
                plaintext = preview,
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = 9uL,
                timelineAt = latestAt,
                deleted = false,
                attachmentKind = null,
                attachmentCount = 0u,
                deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
            ),
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = "message-$groupId",
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = activitySortAt,
        updatedAt = latestAt,
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

    private fun preview(
        messageId: String,
        plaintext: String,
        latestAt: ULong,
    ) = ChatListMessagePreviewFfi(
        messageIdHex = messageId,
        sender = "sender",
        senderDisplayName = "Sender",
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = 9uL,
        timelineAt = latestAt,
        deleted = false,
        attachmentKind = null,
        attachmentCount = 0u,
        deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
    )

    private fun noMessageRow(
        groupId: String,
        title: String,
        updatedAt: ULong,
        lastReadTimelineAt: ULong? = null,
        conversationCreatedAt: ULong = 0uL,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = false,
        pendingConfirmation = false,
        title = title,
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = lastReadTimelineAt,
        conversationCreatedAt = conversationCreatedAt,
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

    private fun group(
        id: String,
        pending: Boolean = false,
    ) = AppGroupRecordFfi(
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
        pendingConfirmation = pending,
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

    private fun message(
        groupId: String,
        recordedAt: ULong,
        plaintext: String = "hello",
        tags: List<MessageTagFfi> = emptyList(),
    ) = AppMessageRecordFfi(
        messageIdHex = "message-$groupId",
        direction = "received",
        groupIdHex = groupId,
        sender = "sender",
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = 9uL,
        tags = tags,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = recordedAt,
        receivedAt = recordedAt,
    )
}
