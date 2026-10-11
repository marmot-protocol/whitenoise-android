package dev.ipf.whitenoise.android.share

import android.Manifest
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.CONVERSATION_SHARE_TARGET_CATEGORY
import dev.ipf.whitenoise.android.notifications.CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA
import dev.ipf.whitenoise.android.notifications.NotificationPreviewPreferences
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.conversationShortcutAccountScope
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.state.ChatListItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
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

    /** A replaced refresh cannot overwrite Direct Share after waiting for the platform's cached inventory. */
    @Test
    fun cancelledPublicationCannotWriteAfterInventoryRead() {
        var current = true
        val published = mutableListOf<List<ShortcutInfoCompat>>()
        val publisher =
            ShareShortcutPublisher(
                context = RuntimeEnvironment.getApplication(),
                maxShortcutCount = { 8 },
                setDynamicShortcuts = { published += it },
                existingShortcuts = {
                    current = false
                    emptyList()
                },
            )
        publisher.publish("acct", listOf(chat("g1", pending = false)), { current }) { it.group.name }
        assertTrue(published.isEmpty())
    }

    /** An account or group removal after label preparation must prevent recreating its Direct Share entry. */
    @Test
    fun revokedPublicationCannotRestoreShortcutsAfterCleanup() {
        val context = RuntimeEnvironment.getApplication()
        val tokens = PinnedConversationTokens.create(context)
        listOf(false, true).forEach { removeAccount ->
            val published = mutableListOf<List<ShortcutInfoCompat>>()
            val publisher =
                ShareShortcutPublisher(
                    context = context,
                    maxShortcutCount = { 8 },
                    setDynamicShortcuts = { published += it },
                    existingShortcuts = {
                        if (removeAccount) tokens.revokeAccount("acct") else tokens.revokeGroup("acct", "g1")
                        emptyList()
                    },
                )
            publisher.publish("acct", listOf(chat("g1", pending = false)), { true }) { it.group.name }
            assertTrue(published.isEmpty())
        }
    }

    /** A coroutine queued before removal must retain its capture even if the publisher starts after cleanup. */
    @Test
    fun queuedRefreshAfterRemovalCannotRestoreShortcuts() {
        val context = RuntimeEnvironment.getApplication()
        val publicationGeneration = PinnedConversationTokens.captureRequest()
        PinnedConversationTokens.create(context).revokeGroup("acct", "g1")
        val published = mutableListOf<List<ShortcutInfoCompat>>()
        val publisher =
            ShareShortcutPublisher(
                context = context,
                maxShortcutCount = { 8 },
                setDynamicShortcuts = { published += it },
                existingShortcuts = { emptyList() },
            )
        publisher.publish(
            "acct",
            listOf(chat("g1", pending = false)),
            { PinnedConversationTokens.isPublicationCurrent(publicationGeneration) },
        ) { it.group.name }
        assertTrue(published.isEmpty())
    }

    /**
     * A refresh captured while native removal is suspended cannot publish before its final generation advances.
     */
    @Test
    fun publicationDuringRemovalCannotRestoreShortcuts() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val published = mutableListOf<List<ShortcutInfoCompat>>()
            val publisher =
                ShareShortcutPublisher(
                    context = context,
                    maxShortcutCount = { 8 },
                    setDynamicShortcuts = { published += it },
                    existingShortcuts = { emptyList() },
                )
            PinnedConversationTokens.create(context).withRemovalRevoked("acct", "g1") {
                publisher.publish("acct", listOf(chat("g1", pending = false)), { true }) { it.group.name }
            }
            assertTrue(published.isEmpty())
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
        assertEquals(
            conversationShortcutAccountScope("acct"),
            republished.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA),
        )
        assertTrue(republished.categories?.contains(CONVERSATION_SHARE_TARGET_CATEGORY) == true)
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

    @Test
    fun publishWithPreviewsOffNeverRestoresPrivateShareMetadata() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false, scrub = { true }))
            var result = emptyList<ShortcutInfoCompat>()
            val publisher =
                ShareShortcutPublisher(
                    context,
                    maxShortcutCount = { 2 },
                    setDynamicShortcuts = { result = it },
                    existingShortcuts = { emptyList() },
                )
            publisher.publish("account", listOf(chat("group", pending = false))) { "Private title" }
            val shortcut = result.single()
            assertEquals(context.getString(R.string.app_name), shortcut.longLabel)
            assertTrue(shortcut.categories.isNullOrEmpty())
            assertTrue(shortcut.extras!!.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN))
        }

    @Test
    fun publishPreparedBeforeOffThenOnStaysGeneric() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            assertTrue(NotificationPreviewPreferences.setEnabled(context, true, scrub = { true }))
            var result = emptyList<ShortcutInfoCompat>()
            val publisher =
                ShareShortcutPublisher(
                    context,
                    maxShortcutCount = { 2 },
                    setDynamicShortcuts = { result = it },
                    existingShortcuts = { emptyList() },
                )
            publisher.publish("account", listOf(chat("group", pending = false))) {
                runBlocking {
                    assertTrue(NotificationPreviewPreferences.setEnabled(context, false, scrub = { true }))
                    assertTrue(NotificationPreviewPreferences.setEnabled(context, true, scrub = { true }))
                }
                "Private title"
            }
            assertEquals(context.getString(R.string.app_name), result.single().longLabel)
            assertTrue(result.single().extras!!.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN))
        }

    /** A shortcut that still backs a live card is kept beside the recent chats, so its channel and card survive. */
    @Test
    fun publish_retainsADynamicShortcutThatStillBacksALiveCard() {
        val card = cardShortcut("other", "pending-group", "Pending title")
        var published = emptyList<ShortcutInfoCompat>()

        publisherWithCards(maxShortcuts = 8, cards = listOf(card)) { published = it }
            .publish("acct", listOf(chat("g1", pending = false))) { it.group.name }

        assertEquals(listOf(conversationShortcutId("acct", "g1"), card.id), published.map { it.id })
        val kept = published.last()
        assertEquals("Pending title", kept.longLabel)
        assertEquals(1, kept.rank)
        assertEquals(
            conversationShortcutAccountScope("other"),
            kept.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA),
        )
        assertTrue(kept.categories.isNullOrEmpty())
    }

    /** Card-backed shortcuts take their slots first, so the lowest-ranked recent chats give theirs up. */
    @Test
    fun publish_cardBackedShortcutsTakeSlotsFromTheLowestRankedTargets() {
        val cards = listOf(cardShortcut("other", "c1", "One", rank = 0), cardShortcut("other", "c2", "Two", rank = 1))
        var published = emptyList<ShortcutInfoCompat>()

        publisherWithCards(maxShortcuts = 3, cards = cards) { published = it }
            .publish(
                "acct",
                listOf(chat("g1", pending = false), chat("g2", pending = false), chat("g3", pending = false)),
            ) { it.group.name }

        assertEquals(
            listOf(conversationShortcutId("acct", "g1"), cards[0].id, cards[1].id),
            published.map { it.id },
        )
    }

    /** A card-backed conversation that is also a recent chat is published once, as the share target. */
    @Test
    fun publish_publishesACardBackedShortcutOnceWhenItIsAlsoARecentChat() {
        val card = cardShortcut("acct", "g2", "Group two")
        var published = emptyList<ShortcutInfoCompat>()

        publisherWithCards(maxShortcuts = 8, cards = listOf(card)) { published = it }
            .publish("acct", listOf(chat("g1", pending = false), chat("g2", pending = false))) { it.group.name }

        assertEquals(
            listOf(conversationShortcutId("acct", "g1"), conversationShortcutId("acct", "g2")),
            published.map { it.id },
        )
        assertTrue(published.all { it.categories?.contains(CONVERSATION_SHARE_TARGET_CATEGORY) == true })
    }

    /** More card-backed shortcuts than the platform allows are capped, never passed over the limit. */
    @Test
    fun publish_neverExceedsThePlatformLimitWithCardBackedShortcuts() {
        val cards = (0..2).map { cardShortcut("other", "c$it", "Card $it", rank = it) }
        var published = emptyList<ShortcutInfoCompat>()

        publisherWithCards(maxShortcuts = 2, cards = cards) { published = it }
            .publish("acct", listOf(chat("g1", pending = false))) { it.group.name }

        assertEquals(cards.take(2).map { it.id }, published.map { it.id })
    }

    /** Without a live card the dynamic inventory is never read and the publication is unchanged. */
    @Test
    fun publish_withoutLiveCardsDoesNotReadTheDynamicInventory() {
        var published = emptyList<ShortcutInfoCompat>()
        val publisher =
            ShareShortcutPublisher(
                context = RuntimeEnvironment.getApplication(),
                maxShortcutCount = { 8 },
                setDynamicShortcuts = { published = it },
                existingShortcuts = { emptyList() },
                dynamicShortcuts = { error("the dynamic inventory is only needed when a card is live") },
                cardShortcutIds = { emptySet() },
            )

        publisher.publish("acct", listOf(chat("g1", pending = false))) { it.group.name }

        assertEquals(listOf(conversationShortcutId("acct", "g1")), published.map { it.id })
    }

    /** A card's own shortcut is unstamped, and it keeps its conversation title while previews are on. */
    @Test
    fun publish_keepsTheTitleOfAnUnstampedRetainedShortcutWhilePreviewsAreOn() {
        val context = RuntimeEnvironment.getApplication()
        val unstamped =
            checkNotNull(
                buildShareShortcut(context, ShareShortcutTarget("other", "pending-group", "Pending title")),
            )
        var published = emptyList<ShortcutInfoCompat>()

        publisherWithCards(maxShortcuts = 8, cards = listOf(unstamped)) { published = it }
            .publish("acct", listOf(chat("g1", pending = false))) { it.group.name }

        assertEquals("Pending title", published.single { it.id == unstamped.id }.longLabel)
    }

    /** A retained shortcut is redacted to the generic label like any other entry once previews are off. */
    @Test
    fun publish_redactsARetainedShortcutWhilePreviewsAreOff() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val card = cardShortcut("other", "pending-group", "Private title")
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false, scrub = { true }))
            var published = emptyList<ShortcutInfoCompat>()

            publisherWithCards(maxShortcuts = 8, cards = listOf(card)) { published = it }
                .publish("acct", listOf(chat("g1", pending = false))) { it.group.name }

            val kept = published.single { it.id == card.id }
            assertEquals(context.getString(R.string.app_name), kept.longLabel)
            assertTrue(kept.extras!!.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN))
        }

    /** A retained shortcut captured before previews were turned off and on again must still end up generic. */
    @Test
    fun publish_retainedShortcutCapturedBeforeOffThenOnStaysGeneric() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            assertTrue(NotificationPreviewPreferences.setEnabled(context, true, scrub = { true }))
            val card = cardShortcut("other", "pending-group", "Private title")
            var published = emptyList<ShortcutInfoCompat>()
            val publisher =
                ShareShortcutPublisher(
                    context = context,
                    maxShortcutCount = { 8 },
                    setDynamicShortcuts = { published = it },
                    existingShortcuts = {
                        runBlocking {
                            assertTrue(NotificationPreviewPreferences.setEnabled(context, false, scrub = { true }))
                            assertTrue(NotificationPreviewPreferences.setEnabled(context, true, scrub = { true }))
                        }
                        emptyList()
                    },
                    dynamicShortcuts = { listOf(card) },
                    cardShortcutIds = { setOf(card.id) },
                )

            publisher.publish("acct", listOf(chat("g1", pending = false))) { it.group.name }

            assertEquals(context.getString(R.string.app_name), published.single { it.id == card.id }.longLabel)
        }

    /** Only conversation shortcut ids of posted cards count, so unrelated cards and cards without one are ignored. */
    @Test
    fun liveCardShortcutIds_readsTheShortcutIdsOfConversationCards() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val conversationId = checkNotNull(conversationShortcutId("acct", "g1"))
        listOf(1 to conversationId, 2 to "unrelated", 3 to null).forEach { (id, shortcutId) ->
            manager.notify(
                id,
                NotificationCompat
                    .Builder(context, "channel")
                    .setSmallIcon(R.drawable.ic_stat_whitenoise)
                    .apply { shortcutId?.let(::setShortcutId) }
                    .build(),
            )
        }

        assertEquals(setOf(conversationId), liveCardShortcutIds(context))
        manager.cancelAll()
    }

    /** Builds a publisher whose platform inventory holds [cards], each backing a live card. */
    private fun publisherWithCards(
        maxShortcuts: Int,
        cards: List<ShortcutInfoCompat>,
        setDynamicShortcuts: (List<ShortcutInfoCompat>) -> Unit,
    ) = ShareShortcutPublisher(
        context = RuntimeEnvironment.getApplication(),
        maxShortcutCount = { maxShortcuts },
        setDynamicShortcuts = setDynamicShortcuts,
        existingShortcuts = { emptyList() },
        dynamicShortcuts = { cards },
        cardShortcutIds = { cards.map { it.id }.toSet() },
    )

    /** Builds a conversation shortcut as the card presenter publishes it, stamped for the current preview policy. */
    private fun cardShortcut(
        accountRef: String,
        groupId: String,
        title: String,
        rank: Int = 0,
    ): ShortcutInfoCompat {
        val context = RuntimeEnvironment.getApplication()
        return checkNotNull(
            buildShareShortcut(
                context,
                ShareShortcutTarget(accountRef, groupId, title),
                rank = rank,
                previewToken = NotificationPreviewPreferences.capture(context),
            ),
        )
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
