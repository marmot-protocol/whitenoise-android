package dev.ipf.whitenoise.android.share

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.state.ChatListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareShortcutPublisherTest {
    private val titleCopy =
        dev.ipf.whitenoise.android.core.GroupTitleCopy(
            inviteFromFormat = "Invite from %1\$s",
            groupOfPeopleFormat = "Group of %1\$d people",
            unknownTitle = "Unknown",
        )

    @Test
    fun buildShareShortcut_usesConversationShortcutIdCategoryAndNoGroupIdExtra() {
        val context = RuntimeEnvironment.getApplication()
        val shortcut =
            buildShareShortcut(
                context = context,
                target =
                    ShareShortcutTarget(
                        accountRef = "acct",
                        groupIdHex = "group-1",
                        title = "Alice",
                    ),
            )
        val expectedId = conversationShortcutId("acct", "group-1")
        assertEquals(expectedId, shortcut?.id)
        assertTrue(
            shortcut?.categories?.contains(
                dev.ipf.whitenoise.android.notifications.CONVERSATION_SHARE_TARGET_CATEGORY,
            ) == true,
        )
        assertNull(shortcut?.intent?.getStringExtra("dev.ipf.whitenoise.android.extra.DIRECT_SHARE_GROUP_ID"))
    }

    @Test
    fun selectShareShortcutTargets_skipsPendingConfirmation() {
        val chats =
            listOf(
                chat("g1", pending = false),
                chat("g2", pending = true),
            )
        val targets =
            selectShareShortcutTargets(
                accountRef = "acct",
                chats = chats,
                limit = 4,
                displayTitle = { item -> item.group.name },
            )
        assertEquals(listOf("g1"), targets.map { it.groupIdHex })
    }

    @Test
    fun selectShareShortcutTargets_usesSanitizedDisplayTitleNotRawGroupId() {
        val unnamed = chat("opaque-group-id-hex", pending = false, name = "")
        val targets =
            selectShareShortcutTargets(
                accountRef = "acct",
                chats = listOf(unnamed),
                limit = 4,
                displayTitle = { titleCopy.unknownTitle },
            )
        assertEquals("Unknown", targets.single().title)
    }

    @Test
    fun publish_usesSetDynamicShortcutsInRankOrder() {
        val context = RuntimeEnvironment.getApplication()
        val publishedIds = mutableListOf<String>()
        val publisher =
            ShareShortcutPublisher(
                context = context,
                maxShortcutCount = { 2 },
                setDynamicShortcuts = { shortcuts -> publishedIds += shortcuts.map { it.id } },
                existingShortcuts = { emptyList() },
                removeLongLivedShortcuts = { },
            )
        val chats =
            listOf(
                chat("g1", pending = false),
                chat("g2", pending = false),
                chat("g3", pending = false),
            )
        publisher.publish(
            accountRef = "acct",
            chats = chats,
            displayTitle = { item -> item.group.name },
        )
        assertEquals(2, publishedIds.size)
        assertEquals(conversationShortcutId("acct", "g1"), publishedIds[0])
        assertEquals(conversationShortcutId("acct", "g2"), publishedIds[1])
    }

    @Test
    fun publish_removesStaleLongLivedConversationShortcuts() {
        val context = RuntimeEnvironment.getApplication()
        val stale =
            buildShareShortcut(
                context,
                ShareShortcutTarget(accountRef = "other-account", groupIdHex = "stale", title = "Stale"),
            )!!
        var removed = emptyList<String>()
        val publisher =
            ShareShortcutPublisher(
                context = context,
                maxShortcutCount = { 1 },
                setDynamicShortcuts = { },
                existingShortcuts = { listOf(stale) },
                removeLongLivedShortcuts = { removed = it },
            )

        publisher.publish("acct", listOf(chat("g1", pending = false))) { it.group.name }

        assertEquals(listOf(stale.id), removed)
    }
}

private fun chat(
    groupId: String,
    pending: Boolean,
    name: String = "Chat $groupId",
): ChatListItem =
    ChatListItem(
        group =
            AppGroupRecordFfi(
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
                archived = false,
                pendingConfirmation = pending,
                unrecoverable = false,
                welcomerAccountIdHex = null,
                viaWelcomeMessageIdHex = null,
                disappearingMessageSecs = 0uL,
            ),
        latest = null,
        otherMemberAccount = null,
        memberCount = 1,
        memberSnapshot = null,
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
