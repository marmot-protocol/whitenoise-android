package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.SystemFolderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFolderChipModelTest {
    @Test
    fun systemChipsHideWhenEmptyAndKeepTheirCounts() {
        val chips =
            chatFolderChipModels(
                folders = ChatFolderPreferences.systemFolders(),
                activeItems = listOf(item("g1", unread = true, members = 3), item("g2", unread = false, members = 2)),
                archivedItems = emptyList(),
                membershipOf = { emptySet() },
            )

        // Archived hides (empty); Unread carries its count; Groups shows for
        // the 3-member chat.
        assertEquals(listOf(SystemFolderKind.UNREAD, SystemFolderKind.GROUPS), chips.map { it.systemKind })
        assertEquals(1, chips.first { it.systemKind == SystemFolderKind.UNREAD }.trailingCount)
    }

    @Test
    fun customFoldersShowOnlyWithMatchingChatsInConfiguredOrder() {
        val custom =
            dev.ipf.whitenoise.android.state
                .ChatFolder("f1", "Work", "", order = 0, isSystem = false, systemKind = null)
        val emptyCustom =
            dev.ipf.whitenoise.android.state
                .ChatFolder("f2", "Empty", "", order = 1, isSystem = false, systemKind = null)

        val chips =
            chatFolderChipModels(
                folders = listOf(emptyCustom.copy(order = 1), custom.copy(order = 0)),
                activeItems = listOf(item("g1", unread = false, members = 2)),
                archivedItems = emptyList(),
                membershipOf = { folderId -> if (folderId == "f1") setOf("g1") else emptySet() },
            )

        assertEquals(listOf("f1"), chips.map { it.folderId })
        assertEquals("Work", chips.single().customLabel)
    }

    @Test
    fun archivedChipCountsItsUnread() {
        val chips =
            chatFolderChipModels(
                folders = ChatFolderPreferences.systemFolders(),
                activeItems = emptyList(),
                archivedItems = listOf(item("g3", unread = true, members = 2), item("g4", unread = false, members = 2)),
                membershipOf = { emptySet() },
            )

        val archived = chips.single { it.systemKind == SystemFolderKind.ARCHIVED }
        assertEquals(1, archived.trailingCount)
        assertTrue(chips.none { it.systemKind == SystemFolderKind.UNREAD })
    }

    private fun item(
        groupIdHex: String,
        unread: Boolean,
        members: Int,
    ): ChatListItem =
        ChatListItem(
            group = group(groupIdHex),
            latest = null,
            otherMemberAccount = null,
            memberCount = members,
            memberSnapshot = null,
            projection = row(groupIdHex, unread),
        )

    private fun row(
        groupIdHex: String,
        unread: Boolean,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupIdHex,
        archived = false,
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
        updatedAt = 1uL,
    )

    private fun group(id: String) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
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
            imageHashHex = null,
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
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net"),
                ),
        )
}
