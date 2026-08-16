package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFolderRulesTest {
    @Test
    fun memberRuleFollowsRosterJoinAndLeave() {
        val rule = ChatFolderRule(includeMemberPubkeys = setOf("AA"))
        val joined = item("g1", members = listOf("aa", "bb"))
        val left = item("g1", members = listOf("bb"))

        assertEquals(setOf("g1"), folderIds(listOf(joined), rule = rule))
        assertEquals(emptySet<String>(), folderIds(listOf(left), rule = rule))
    }

    @Test
    fun resolvedDmCounterpartStillMatchesWhileRosterIsPending() {
        val rule = ChatFolderRule(includeMemberPubkeys = setOf("cc"))
        val dm = item("g2", members = null, presentationOtherMember = "CC")

        assertEquals(null, dm.otherMemberAccount)
        assertEquals(setOf("g2"), folderIds(listOf(dm), rule = rule))
    }

    @Test
    fun unreadOnlyAddsAndDropsWithReadState() {
        val rule = ChatFolderRule(includeMemberPubkeys = setOf("aa"), unreadOnly = true)
        val unread = item("g1", members = listOf("aa"), unread = true)
        val read = item("g1", members = listOf("aa"), unread = false)

        assertEquals(setOf("g1"), folderIds(listOf(unread), rule = rule))
        assertEquals(emptySet<String>(), folderIds(listOf(read), rule = rule))
    }

    @Test
    fun standaloneUnreadOnlyRuleMatchesEveryUnreadChat() {
        val rule = ChatFolderRule(unreadOnly = true)
        val items = listOf(item("g1", unread = true), item("g2", unread = false))

        assertEquals(setOf("g1"), folderIds(items, rule = rule))
    }

    @Test
    fun includeMutedGatesMutedChatsInAndOut() {
        val excluding = ChatFolderRule(includeMemberPubkeys = setOf("aa"))
        val including = excluding.copy(includeMuted = true)
        val items = listOf(item("g1", members = listOf("aa")))
        val muted = { id: String -> id == "g1" }

        assertEquals(emptySet<String>(), folderIds(items, rule = excluding, isMuted = muted))
        assertEquals(setOf("g1"), folderIds(items, rule = including, isMuted = muted))
    }

    @Test
    fun manualMembershipIsAdditiveWithRuleMatchesAndNeverFiltered() {
        val rule = ChatFolderRule(includeMemberPubkeys = setOf("aa"), unreadOnly = true)
        val items =
            listOf(
                item("g1", members = listOf("aa"), unread = true),
                item("g2", members = listOf("bb"), unread = false),
            )

        // g2 fails every rule criterion and is muted, but a manual add keeps
        // it in the folder — rule constraints never filter manual members.
        assertEquals(
            setOf("g1", "g2"),
            folderIds(items, manual = setOf("g2"), rule = rule, isMuted = { it == "g2" }),
        )
    }

    @Test
    fun nullRuleAndEmptyRuleStayManualOnly() {
        val items = listOf(item("g1", members = listOf("aa"), unread = true))

        assertEquals(setOf("g9"), folderIds(items, manual = setOf("g9"), rule = null))
        assertEquals(setOf("g9"), folderIds(items, manual = setOf("g9"), rule = ChatFolderRule()))
    }

    @Test
    fun keywordMatchesTheDisplayedTitleCaseInsensitively() {
        val rule = ChatFolderRule(keyword = "WORK")
        val items = listOf(item("g1"), item("g2"))
        val titled = { item: ChatListItem -> if (item.id == "g1") "Deep work chat" else "Family" }
        val renamed = { item: ChatListItem -> if (item.id == "g1") "Deep focus chat" else "Family" }

        assertEquals(setOf("g1"), folderIds(items, rule = rule, displayTitle = titled))
        // A title edit re-evaluates: the chat drops out once nothing matches.
        assertEquals(emptySet<String>(), folderIds(items, rule = rule, displayTitle = renamed))
    }

    @Test
    fun keywordMatchesTheGroupDescription() {
        val rule = ChatFolderRule(keyword = "research")
        val described = item("g1", description = "Protocol research workgroup")
        val redescribed = item("g1", description = "Protocol reading group")

        assertEquals(setOf("g1"), folderIds(listOf(described), rule = rule))
        assertEquals(emptySet<String>(), folderIds(listOf(redescribed), rule = rule))
    }

    @Test
    fun keywordIsAdditiveWithTheMemberCriterion() {
        val rule = ChatFolderRule(includeMemberPubkeys = setOf("aa"), keyword = "work")
        val items =
            listOf(
                item("g1", members = listOf("aa")),
                item("g2", description = "work stuff"),
                item("g3"),
            )

        assertEquals(setOf("g1", "g2"), folderIds(items, rule = rule))
    }

    @Test
    fun keywordRespectsTheUnreadAndMuteConstraints() {
        val rule = ChatFolderRule(keyword = "work", unreadOnly = true)
        val items =
            listOf(
                item("g1", description = "work", unread = true),
                item("g2", description = "work", unread = false),
            )

        assertEquals(setOf("g1"), folderIds(items, rule = rule))
        assertEquals(emptySet<String>(), folderIds(items, rule = rule, isMuted = { true }))
    }

    @Test
    fun standaloneGroupsOnlyRuleMatchesEveryGroupChat() {
        val rule = ChatFolderRule(groupsOnly = true)
        val items = listOf(item("g1"), item("g2", dm = true))

        assertEquals(setOf("g1"), folderIds(items, rule = rule))
    }

    @Test
    fun groupsOnlyConstrainsKeywordAndMemberMatches() {
        val rule = ChatFolderRule(keyword = "work", groupsOnly = true)
        val items =
            listOf(
                item("g1", description = "work"),
                item("g2", description = "work", dm = true),
            )

        assertEquals(setOf("g1"), folderIds(items, rule = rule))
    }

    @Test
    fun archivedOnlyRuleFollowsTheRowsOwnArchivedFlag() {
        val archivedRule = ChatFolderRule(archivedOnly = true)
        val activeRule = ChatFolderRule(keyword = "work")
        val items =
            listOf(
                item("g1", description = "work", archived = true),
                item("g2", description = "work"),
            )

        // The pure archived rule (the Archived default) matches archived rows
        // only; a rule without archivedOnly never picks up an archived row —
        // regardless of which source list a caller hands in.
        assertEquals(setOf("g1"), folderIds(items, rule = archivedRule))
        assertEquals(setOf("g2"), folderIds(items, rule = activeRule))
    }

    private fun folderIds(
        items: List<ChatListItem>,
        manual: Set<String> = emptySet(),
        rule: ChatFolderRule?,
        isMuted: (String) -> Boolean = { false },
        displayTitle: (ChatListItem) -> String = { "" },
    ): Set<String> =
        chatFolderChatIds(
            items = items,
            manualChatIds = manual,
            rule = rule,
            activeAccountIdHex = null,
            isMuted = isMuted,
            displayTitle = displayTitle,
        )

    private fun item(
        groupIdHex: String,
        members: List<String>? = null,
        otherMember: String? = null,
        presentationOtherMember: String? = otherMember,
        unread: Boolean = false,
        description: String = "",
        dm: Boolean = false,
        archived: Boolean = false,
    ): ChatListItem =
        ChatListItem(
            group = group(groupIdHex, description, archived),
            latest = null,
            otherMemberAccount = otherMember,
            memberCount = members?.size ?: 0,
            memberSnapshot =
                members?.let { roster ->
                    GroupMemberSnapshot(
                        roster.map { AppGroupMemberRecordFfi(memberIdHex = it, account = null, local = false) },
                    )
                },
            presentationOtherMemberAccount = presentationOtherMember,
            projection = row(groupIdHex, unread, dm, archived),
        )

    private fun row(
        groupIdHex: String,
        unread: Boolean,
        dm: Boolean = false,
        rowArchived: Boolean = false,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupIdHex,
        archived = rowArchived,
        pendingConfirmation = false,
        title = "Group $groupIdHex",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = if (unread) 1uL else 0uL,
        hasUnread = unread,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 0uL,
        activitySortAt = 0uL,
        updatedAt = 1uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = if (dm) ChatConversationKindFfi.DIRECT else ChatConversationKindFfi.GROUP,
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
        description: String,
        archived: Boolean = false,
    ) = AppGroupRecordFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        groupIdHex = id,
        protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
        profilePresent = false,
        endpoint = "endpoint-$id",
        name = "",
        description = description,
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "nostr-$id",
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia = encryptedMedia(),
        archived = archived,
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
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net"),
                ),
        )
}
