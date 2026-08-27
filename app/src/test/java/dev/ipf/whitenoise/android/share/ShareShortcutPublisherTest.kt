package dev.ipf.whitenoise.android.share

import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.notifications.CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA
import dev.ipf.whitenoise.android.notifications.conversationShortcutAccountScope
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.state.ChatListItem
import org.junit.After
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

    @After
    fun clearPlatformShortcuts() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(RuntimeEnvironment.getApplication())
    }

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
        assertEquals(
            conversationShortcutAccountScope("acct"),
            shortcut?.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA),
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
        var published = emptyList<ShortcutInfoCompat>()
        val publisher =
            ShareShortcutPublisher(
                context = context,
                maxShortcutCount = { 2 },
                setDynamicShortcuts = { shortcuts -> published = shortcuts },
                existingShortcuts = { emptyList() },
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
        assertEquals(2, published.size)
        assertEquals(conversationShortcutId("acct", "g1"), published[0].id)
        assertEquals(conversationShortcutId("acct", "g2"), published[1].id)
        assertEquals(listOf(0, 1), published.map { it.rank })
    }

    @Test
    fun publish_rebuildsRichShortcutWithItsCurrentRecencyRank() {
        val context = RuntimeEnvironment.getApplication()
        val shortcutId = checkNotNull(conversationShortcutId("acct", "g2"))
        val person = Person.Builder().setName("Alice").build()
        val rich =
            ShortcutInfoCompat
                .Builder(context, shortcutId)
                .setShortLabel("Alice")
                .setLongLabel("Alice")
                .setIntent(buildShareShortcutIntent(context))
                .setPerson(person)
                .setLocusId(LocusIdCompat(shortcutId))
                .setLongLived(true)
                .build()
        var published = emptyList<ShortcutInfoCompat>()
        val publisher =
            ShareShortcutPublisher(
                context = context,
                maxShortcutCount = { 2 },
                setDynamicShortcuts = { shortcuts -> published = shortcuts },
                existingShortcuts = { listOf(rich) },
            )

        publisher.publish(
            accountRef = "acct",
            chats = listOf(chat("g1", pending = false), chat("g2", pending = false)),
            displayTitle = { item -> item.group.name },
        )

        val republished = published.single { it.id == shortcutId }
        assertEquals(1, republished.rank)
        assertEquals(rich.locusId, republished.locusId)
        assertEquals(rich.intent, republished.intent)
        assertEquals(rich.extras, republished.extras)
    }

    @Test
    fun publish_platformReadbackPreservesExplicitRankOrder() {
        val context = RuntimeEnvironment.getApplication()
        val chats =
            listOf(
                chat("g1", pending = false),
                chat("g2", pending = false),
                chat("g3", pending = false),
            )

        ShareShortcutPublisher(context).publish("acct", chats) { item -> item.group.name }

        val readBack = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }
        assertEquals(listOf(0, 1, 2), readBack.map { it.rank })
        assertEquals(
            listOf("g1", "g2", "g3").map { groupId -> conversationShortcutId("acct", groupId) },
            readBack.map { it.id },
        )
    }

    @Test
    fun publish_doesNotRepublishAnotherAccountsCachedShortcut() {
        val context = RuntimeEnvironment.getApplication()
        val stale =
            buildShareShortcut(
                context,
                ShareShortcutTarget(accountRef = "other-account", groupIdHex = "stale", title = "Stale"),
            )!!
        var published = emptyList<String>()
        val publisher =
            ShareShortcutPublisher(
                context = context,
                maxShortcutCount = { 1 },
                setDynamicShortcuts = { published = it.map { shortcut -> shortcut.id } },
                existingShortcuts = { listOf(stale) },
            )

        publisher.publish("acct", listOf(chat("g1", pending = false))) { it.group.name }

        assertEquals(listOf(conversationShortcutId("acct", "g1")), published)
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
                leaveRequestPending = false,
                leaveRequestedAtMs = null,
                disbanding = false,
                disbanded = false,
                disbandRequest = null,
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
