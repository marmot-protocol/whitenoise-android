package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.chatFolderChatIds
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.SystemFolderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFolderChipModelTest {
    @Test
    fun defaultChipsHideWhenEmptyAndCountTheirUnread() {
        val chips =
            chips(
                folders = ChatFolderPreferences.systemFolders(),
                activeItems = listOf(item("g1", unread = true, members = 3), item("g2", unread = false, members = 2)),
            )

        // Archived hides (empty); Unread carries its count; Groups shows for
        // the 3-member chat — all purely from the seeded rules.
        assertEquals(listOf(SystemFolderKind.UNREAD, SystemFolderKind.GROUPS), chips.map { it.systemKind })
        assertEquals(1, chips.first { it.systemKind == SystemFolderKind.UNREAD }.trailingCount)
        assertEquals(1, chips.first { it.systemKind == SystemFolderKind.GROUPS }.trailingCount)
    }

    @Test
    fun customFoldersShowOnlyWithMatchingChatsInConfiguredOrder() {
        val custom = ChatFolder("f1", "Work", "", order = 0, systemKind = null)
        val emptyCustom = ChatFolder("f2", "Empty", "", order = 1, systemKind = null)

        val chips =
            chips(
                folders = listOf(emptyCustom.copy(order = 1), custom.copy(order = 0)),
                activeItems = listOf(item("g1", unread = false, members = 2)),
                manual = mapOf("f1" to setOf("g1")),
            )

        assertEquals(listOf("f1"), chips.map { it.folderId })
        assertEquals("Work", chips.single().customLabel)
    }

    @Test
    fun archivedChipCountsItsUnread() {
        val chips =
            chips(
                folders = ChatFolderPreferences.systemFolders(),
                archivedItems =
                    listOf(
                        item("g3", unread = true, members = 2, archived = true),
                        item("g4", unread = false, members = 2, archived = true),
                    ),
            )

        val archived = chips.single { it.systemKind == SystemFolderKind.ARCHIVED }
        assertEquals(1, archived.trailingCount)
        assertTrue(chips.none { it.systemKind == SystemFolderKind.UNREAD })
    }

    @Test
    fun renamedDefaultCarriesItsStoredNameOntoTheChip() {
        val renamed =
            ChatFolderPreferences
                .systemFolders()
                .map { if (it.systemKind == SystemFolderKind.UNREAD) it.copy(name = "Catch up") else it }

        val chips =
            chips(
                folders = renamed,
                activeItems = listOf(item("g1", unread = true, members = 3)),
            )

        assertEquals("Catch up", chips.first { it.systemKind == SystemFolderKind.UNREAD }.customLabel)
    }

    @Test
    fun editedDefaultRuleDrivesItsChipMembership() {
        // Groups default re-ruled to unread-only: it must follow the edited
        // rule, not a hardcoded kind branch.
        val rules =
            defaultRules() +
                (ChatFolderPreferences.SYSTEM_FOLDER_GROUPS_ID to ChatFolderRule(unreadOnly = true))

        val chips =
            chips(
                folders = ChatFolderPreferences.systemFolders(),
                activeItems = listOf(item("g1", unread = false, members = 3), item("g2", unread = true, members = 2)),
                rules = rules,
            )

        // g1 is the only group but is read — the re-ruled chip tracks unread
        // g2 instead.
        assertEquals(1, chips.first { it.systemKind == SystemFolderKind.GROUPS }.trailingCount)
    }

    // Wires the real rule evaluator in as the membership resolver, per folder
    // and against that folder's own source list — the same shape ChatsScreen
    // hands to the chip row.
    private fun chips(
        folders: List<ChatFolder>,
        activeItems: List<ChatListItem> = emptyList(),
        archivedItems: List<ChatListItem> = emptyList(),
        rules: Map<String, ChatFolderRule> = defaultRules(),
        manual: Map<String, Set<String>> = emptyMap(),
    ) = chatFolderChipModels(
        folders = folders,
        activeItems = activeItems,
        archivedItems = archivedItems,
        activeAccountIdHex = null,
        ruleOf = { rules[it] },
        membershipOf = { folderId ->
            val rule = rules[folderId]
            chatFolderChatIds(
                items = if (rule?.archivedOnly == true) archivedItems else activeItems,
                manualChatIds = manual[folderId].orEmpty(),
                rule = rule,
                activeAccountIdHex = null,
                isMuted = { false },
                displayTitle = { "" },
            )
        },
    )

    private fun defaultRules(): Map<String, ChatFolderRule> =
        ChatFolderPreferences.systemFolders().associate { folder ->
            folder.id to ChatFolderPreferences.defaultRuleFor(folder.systemKind!!)
        }

    private fun item(
        groupIdHex: String,
        unread: Boolean,
        members: Int,
        archived: Boolean = false,
    ): ChatListItem =
        ChatListItem(
            group = group(groupIdHex, archived),
            latest = null,
            otherMemberAccount = null,
            memberCount = members,
            memberSnapshot = null,
            projection = row(groupIdHex, unread, archived),
        )

    private fun row(
        groupIdHex: String,
        unread: Boolean,
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
        archived: Boolean = false,
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
