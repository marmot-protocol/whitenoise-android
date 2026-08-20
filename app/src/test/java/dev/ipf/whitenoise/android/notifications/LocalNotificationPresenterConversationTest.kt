package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.CONVERSATION_SHARE_TARGET_CATEGORY
import dev.ipf.whitenoise.android.share.ShareShortcutTarget
import dev.ipf.whitenoise.android.share.buildShareShortcut
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("LargeClass") // Conversation-channel integration scenarios share one expensive presenter fixture.
class LocalNotificationPresenterConversationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private var publishedShortcut: ShortcutInfoCompat? = null
    private var publishedShortcutCount = 0
    private lateinit var presenter: LocalNotificationPresenter

    @Before
    fun setUp() {
        manager.cancelAll()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        ConversationVibrationPreferences(context).setPattern(
            "account-a",
            "group-a",
            ConversationVibrationPattern.SYSTEM_DEFAULT,
        )
        publishedShortcut = null
        publishedShortcutCount = 0
        presenter =
            LocalNotificationPresenter(
                context = context,
                shortcutPublisher = { shortcut ->
                    publishedShortcut = shortcut
                    publishedShortcutCount += 1
                    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                },
            )
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun conversationShortcutRepublishPreservesDirectShareCategory() {
        val shareShortcut =
            buildShareShortcut(
                context = context,
                target =
                    ShareShortcutTarget(
                        accountRef = "account-a",
                        groupIdHex = "group-a",
                        title = "General",
                    ),
            )!!
        ShortcutManagerCompat.pushDynamicShortcut(context, shareShortcut)
        presenter.ensureChannels()
        runBlocking {
            presenter.show(
                update(isMention = false),
                previewTextOverride = "hi",
                directShareEligible = true,
                shortNpub = { "npub1test" },
            )
        }
        assertTrue(
            checkNotNull(publishedShortcut).categories?.contains(CONVERSATION_SHARE_TARGET_CATEGORY) == true,
        )
    }

    @Test
    fun backgroundAccountShortcutOmitsDirectShareCategory() {
        presenter.ensureChannels()
        runBlocking {
            presenter.show(
                update(isMention = false),
                previewTextOverride = "hi",
                directShareEligible = false,
                shortNpub = { "npub1test" },
            )
        }
        val shortcut = checkNotNull(publishedShortcut)
        assertTrue(conversationShortcutIsRich(shortcut))
        assertFalse(shortcut.categories?.contains(CONVERSATION_SHARE_TARGET_CATEGORY) == true)
    }

    @Test
    fun becomingActiveRepublishesShortcutWithDirectShareCategory() {
        presenter.ensureChannels()
        runBlocking {
            presenter.show(
                update(isMention = false),
                previewTextOverride = "hi",
                directShareEligible = false,
                shortNpub = { "npub1test" },
            )
            presenter.show(
                update(isMention = false),
                previewTextOverride = "hi",
                directShareEligible = true,
                shortNpub = { "npub1test" },
            )
        }

        assertEquals(2, publishedShortcutCount)
        assertTrue(
            checkNotNull(publishedShortcut).categories?.contains(CONVERSATION_SHARE_TARGET_CATEGORY) == true,
        )
    }

    @Test
    fun redactedPublicVersionCarriesGenericBodyText() {
        presenter.ensureChannels()
        runBlocking {
            presenter.show(
                update(isMention = false),
                previewTextOverride = "hi",
                directShareEligible = false,
                shortNpub = { "npub1test" },
            )
        }

        // The public variant replaces the card when the OS hides sensitive
        // content. Without a body line it renders as an icon+header shell
        // that reads as a broken notification. The private card's
        // collapsed text is already guaranteed by MessagingStyle extras.
        val publicVersion =
            checkNotNull(
                manager.activeNotifications
                    .single()
                    .notification.publicVersion,
            )
        assertEquals(
            context.getString(R.string.notification_hidden_content),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun clearingAccountShortcutsInvalidatesSnapshotForIdenticalRepublish() {
        presenter.ensureChannels()
        runBlocking {
            presenter.show(
                update(isMention = false),
                previewTextOverride = "hi",
                directShareEligible = true,
                shortNpub = { "npub1test" },
            )
        }
        presenter.clearConversationShortcuts()
        runBlocking {
            presenter.show(
                update(isMention = false),
                previewTextOverride = "hi",
                directShareEligible = true,
                shortNpub = { "npub1test" },
            )
        }

        assertEquals(2, publishedShortcutCount)
    }

    @Test
    fun shareShortcutPublisherPreservesRichNotificationShortcut() {
        presenter.ensureChannels()
        runBlocking {
            presenter.show(
                update(isMention = false),
                previewTextOverride = "hi",
                directShareEligible = true,
                shortNpub = { "npub1test" },
            )
        }
        val rich = checkNotNull(publishedShortcut)
        var synced: ShortcutInfoCompat? = null
        val publisher =
            dev.ipf.whitenoise.android.share.ShareShortcutPublisher(
                context = context,
                maxShortcutCount = { 4 },
                setDynamicShortcuts = { shortcuts -> synced = shortcuts.single() },
                existingShortcuts = { listOf(rich) },
                removeLongLivedShortcuts = { },
            )
        publisher.publish(
            accountRef = "account-a",
            chats =
                listOf(
                    chatListItem("group-a", "General"),
                ),
            displayTitle = { it.group.name },
        )
        assertSame(rich, synced)
        assertTrue(conversationShortcutIsRich(checkNotNull(synced)))
        assertTrue(synced.categories?.contains(CONVERSATION_SHARE_TARGET_CATEGORY) == true)
    }

    @Test
    fun groupMessagePostsOnTheGroupConversationChannelWithTheConversationShortcut() {
        presenter.ensureChannels()

        val posted =
            runBlocking {
                presenter.show(update(isMention = false), previewTextOverride = "hi", shortNpub = { "npub1test" })
            }

        assertTrue(posted)
        val shortcutId = conversationShortcutId("account-a", "group-a")
        val notification = manager.activeNotifications.single().notification
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(NotificationChannelSpec.GROUP_MESSAGES.id, shortcutId!!),
            notification.channelId,
        )
        assertEquals(shortcutId, notification.shortcutId)
        val shortcut = checkNotNull(publishedShortcut)
        assertEquals(shortcutId, shortcut.id)
        assertNotNull(shortcut.icon)
        assertEquals(IconCompat.TYPE_BITMAP, shortcut.icon!!.type)
        assertEquals(
            "message",
            notification.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX),
        )
    }

    @Test
    fun coldAvatarsPostUsefulCardBeforeSilentSameKeyEnrichment() {
        val posts = mutableListOf<Triple<String, Int, Notification>>()
        var pendingEnrichment: (suspend () -> Unit)? = null
        var shortcutPublishCount = 0
        var shortcutWasPublishedBeforeFirstPost = false
        val resolvedAvatar = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val twoStagePresenter =
            LocalNotificationPresenter(
                context = context,
                shortcutPublisher = { shortcut ->
                    publishedShortcut = shortcut
                    shortcutPublishCount += 1
                },
                notificationPoster = { notificationManager, tag, id, notification ->
                    if (posts.isEmpty()) {
                        shortcutWasPublishedBeforeFirstPost = publishedShortcut != null
                    }
                    posts += Triple(tag, id, notification)
                    notificationManager.notify(tag, id, notification)
                },
                cachedAvatarBitmap = { null },
                avatarBitmapResolver = { resolvedAvatar },
                enrichmentLauncher = { pendingEnrichment = it },
            )
        twoStagePresenter.ensureChannels()

        assertTrue(
            runBlocking {
                twoStagePresenter.show(
                    update(isMention = false),
                    previewTextOverride = "hi",
                    conversationAvatarUrl = "https://example.com/group.png",
                    senderAvatarUrl = "https://example.com/alice.png",
                    shortNpub = { "npub1test" },
                )
            },
        )

        assertUsefulInitialPost(posts)
        assertTrue(shortcutWasPublishedBeforeFirstPost)
        assertEquals(1, shortcutPublishCount)

        runBlocking { checkNotNull(pendingEnrichment).invoke() }

        assertEquals(2, posts.size)
        assertEquals(posts[0].first, posts[1].first)
        assertEquals(posts[0].second, posts[1].second)
        assertTrue(posts[1].third.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(0, posts[1].third.defaults)
        assertNull(posts[1].third.sound)
        assertNotNull(publishedShortcut)
        assertEquals(2, shortcutPublishCount)
        val messages =
            checkNotNull(
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posts[1].third),
            ).messages
        assertEquals(listOf("hi"), messages.map { it.text.toString() })
        assertNotNull(messages.single().person?.icon)
    }

    private fun assertUsefulInitialPost(posts: List<Triple<String, Int, Notification>>) {
        assertEquals(1, posts.size)
        assertNotNull(publishedShortcut)
        val notification = posts.single().third
        assertEquals(
            "hi",
            notification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                ?.toString(),
        )
        assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertNotNull(
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
                ?.messages
                ?.single()
                ?.person
                ?.icon,
        )
    }

    @Test
    fun cachedAvatarsKeepTheAlertToOnePost() {
        val posts = mutableListOf<Notification>()
        val cachedAvatar = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cachedPresenter =
            LocalNotificationPresenter(
                context = context,
                shortcutPublisher = { shortcut -> publishedShortcut = shortcut },
                notificationPoster = { notificationManager, tag, id, notification ->
                    posts += notification
                    notificationManager.notify(tag, id, notification)
                },
                cachedAvatarBitmap = { cachedAvatar },
                avatarBitmapResolver = { error("cached avatars must not be fetched") },
                enrichmentLauncher = { error("cached avatars must not launch detached work") },
            )
        cachedPresenter.ensureChannels()

        assertTrue(
            runBlocking {
                cachedPresenter.show(
                    update(isMention = false),
                    conversationAvatarUrl = "https://example.com/group.png",
                    senderAvatarUrl = "https://example.com/alice.png",
                    shortNpub = { "npub1test" },
                )
            },
        )

        assertEquals(1, posts.size)
        assertNotNull(publishedShortcut)
        assertNotNull(
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(posts.single())
                ?.messages
                ?.single()
                ?.person
                ?.icon,
        )
    }

    @Test
    fun olderAvatarEnrichmentCannotOverwriteANewerMessageGeneration() {
        val posts = mutableListOf<Notification>()
        val pendingEnrichments = mutableListOf<suspend () -> Unit>()
        val twoStagePresenter =
            LocalNotificationPresenter(
                context = context,
                shortcutPublisher = { },
                notificationPoster = { notificationManager, tag, id, notification ->
                    posts += notification
                    notificationManager.notify(tag, id, notification)
                },
                cachedAvatarBitmap = { null },
                avatarBitmapResolver = { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) },
                enrichmentLauncher = { pendingEnrichments += it },
            )
        twoStagePresenter.ensureChannels()

        runBlocking {
            twoStagePresenter.show(
                update(isMention = false, messageIdHex = "first"),
                previewTextOverride = "first",
                senderAvatarUrl = "https://example.com/first.png",
                shortNpub = { "npub1test" },
            )
            twoStagePresenter.show(
                update(isMention = false, messageIdHex = "second"),
                previewTextOverride = "second",
                senderAvatarUrl = "https://example.com/second.png",
                shortNpub = { "npub1test" },
            )
        }
        assertEquals(2, pendingEnrichments.size)

        runBlocking {
            pendingEnrichments[1].invoke()
            pendingEnrichments[0].invoke()
        }

        assertEquals(3, posts.size)
        val active = manager.activeNotifications.single().notification
        assertEquals(
            "second",
            active.extras.getString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX),
        )
        assertEquals(
            listOf("first", "second"),
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(active)
                ?.messages
                ?.map { it.text.toString() },
        )
    }

    @Test
    fun dismissalWhileAvatarLoadsPreventsCardResurrection() {
        val posts = mutableListOf<Notification>()
        var pendingEnrichment: (suspend () -> Unit)? = null
        val twoStagePresenter =
            LocalNotificationPresenter(
                context = context,
                shortcutPublisher = { },
                notificationPoster = { notificationManager, tag, id, notification ->
                    posts += notification
                    notificationManager.notify(tag, id, notification)
                },
                cachedAvatarBitmap = { null },
                avatarBitmapResolver = { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) },
                enrichmentLauncher = { pendingEnrichment = it },
            )
        twoStagePresenter.ensureChannels()
        runBlocking {
            twoStagePresenter.show(
                update(isMention = false),
                senderAvatarUrl = "https://example.com/alice.png",
                shortNpub = { "npub1test" },
            )
            twoStagePresenter.dismissConversationMessages("account-a", "group-a")
            checkNotNull(pendingEnrichment).invoke()
        }

        assertEquals(1, posts.size)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun activeInviteIdentityRefreshUpdatesInPlaceWithoutAlertingAgain() {
        presenter.ensureChannels()
        val invite = update(isMention = false, trigger = NotificationTriggerFfi.GROUP_INVITE)

        runBlocking {
            presenter.show(
                invite,
                senderNameOverride = "npub1alice",
                shortNpub = { "npub1alice" },
            )
        }
        assertTrue(presenter.isGroupInviteNotificationActive(invite))

        runBlocking {
            presenter.show(
                invite,
                senderNameOverride = "Alice",
                silentUpdate = true,
                shortNpub = { "npub1alice" },
            )
        }

        val active = manager.activeNotifications.single()
        assertEquals(invite.notificationKey, active.tag)
        assertTrue(active.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(
            active.notification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString()
                .contains("Alice"),
        )
    }

    @Test
    fun futurePrimaryMessagesUseTheSelectedVersionedVibrationChannel() {
        presenter.ensureChannels()
        // The presenter already exists: changing the preference must reroute it
        // without requiring a process restart.
        ConversationVibrationPreferences(context).setPattern(
            "account-a",
            "group-a",
            ConversationVibrationPattern.DOUBLE,
        )

        runBlocking {
            presenter.show(update(isMention = false), previewTextOverride = "hi", shortNpub = { "npub1test" })
        }

        val shortcutId = conversationShortcutId("account-a", "group-a")!!
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.GROUP_MESSAGES.id,
                shortcutId,
                ConversationVibrationPattern.DOUBLE,
            ),
            manager.activeNotifications
                .single()
                .notification.channelId,
        )
    }

    @Test
    fun changingPatternReroutesAnExistingConversationCardToTheActiveChannel() {
        presenter.ensureChannels()
        assertTrue(
            runBlocking {
                presenter.show(
                    update(isMention = false, messageIdHex = "first"),
                    previewTextOverride = "first",
                    shortNpub = { "npub1test" },
                )
            },
        )
        ConversationVibrationPreferences(context).setPattern(
            "account-a",
            "group-a",
            ConversationVibrationPattern.DOUBLE,
        )

        assertTrue(
            runBlocking {
                presenter.show(
                    update(isMention = false, messageIdHex = "second"),
                    previewTextOverride = "second",
                    shortNpub = { "npub1test" },
                )
            },
        )

        val shortcutId = conversationShortcutId("account-a", "group-a")!!
        val active = manager.activeNotifications.single().notification
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.GROUP_MESSAGES.id,
                shortcutId,
                ConversationVibrationPattern.DOUBLE,
            ),
            active.channelId,
        )
        assertEquals(
            listOf("first", "second"),
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(active)
                ?.messages
                ?.map { it.text.toString() },
        )
    }

    @Test
    fun inheritedMentionUsesTheGlobalChannelRegardlessOfPrimaryVibration() {
        presenter.ensureChannels()
        ConversationVibrationPreferences(context).setPattern(
            "account-a",
            "group-a",
            ConversationVibrationPattern.LONG,
        )

        runBlocking {
            presenter.show(update(isMention = true), previewTextOverride = "hi", shortNpub = { "npub1test" })
        }

        assertEquals(
            NotificationChannelSpec.MENTIONS.id,
            manager.activeNotifications
                .single()
                .notification.channelId,
        )
    }

    @Test
    fun systemDefaultRoutesBackToTheLegacyConversationChannel() {
        presenter.ensureChannels()
        val preferences = ConversationVibrationPreferences(context)
        preferences.setPattern("account-a", "group-a", ConversationVibrationPattern.DOUBLE)
        preferences.setPattern("account-a", "group-a", ConversationVibrationPattern.SYSTEM_DEFAULT)

        runBlocking {
            presenter.show(update(isMention = false), previewTextOverride = "hi", shortNpub = { "npub1test" })
        }

        val shortcutId = conversationShortcutId("account-a", "group-a")!!
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.GROUP_MESSAGES.id,
                shortcutId,
            ),
            manager.activeNotifications
                .single()
                .notification.channelId,
        )
    }

    @Test
    fun processRestartKeepsPostingThroughThePersistedSelection() {
        presenter.ensureChannels()
        ConversationVibrationPreferences(context).setPattern(
            "account-a",
            "group-a",
            ConversationVibrationPattern.LONG,
        )
        val restartedPresenter = LocalNotificationPresenter(context)

        runBlocking {
            restartedPresenter.show(
                update(isMention = false),
                previewTextOverride = "hi",
                shortNpub = { "npub1test" },
            )
        }

        val shortcutId = conversationShortcutId("account-a", "group-a")!!
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.GROUP_MESSAGES.id,
                shortcutId,
                ConversationVibrationPattern.LONG,
            ),
            manager.activeNotifications
                .single()
                .notification.channelId,
        )
    }

    @Test
    fun accountSwitchDoesNotReuseAnotherAccountsSelectionForTheSameGroup() {
        presenter.ensureChannels()
        ConversationVibrationPreferences(context).setPattern(
            "account-a",
            "group-a",
            ConversationVibrationPattern.DOUBLE,
        )

        runBlocking {
            presenter.show(
                update(isMention = false, accountRef = "account-b"),
                previewTextOverride = "hi",
                shortNpub = { "npub1test" },
            )
        }

        val shortcutId = conversationShortcutId("account-b", "group-a")!!
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.GROUP_MESSAGES.id,
                shortcutId,
            ),
            manager.activeNotifications
                .single()
                .notification.channelId,
        )
    }

    @Test
    fun groupMessageOffersReplyAndTheFirstTwoCustomizedQuickReactions() {
        RecentEmojiPreferences.saveQuickReactions(context, listOf("🥳", "🔥", "😂", "👍"))
        try {
            presenter.ensureChannels()

            runBlocking {
                presenter.show(update(isMention = false), previewTextOverride = "hi", shortNpub = { "npub1test" })
            }

            assertEquals(
                listOf(context.getString(R.string.reply), "🥳", "🔥"),
                manager.activeNotifications
                    .single()
                    .notification.actions
                    .map { it.title.toString() },
            )
        } finally {
            RecentEmojiPreferences.resetQuickReactions(context)
        }
    }

    @Test
    fun inheritedMentionPostsOnTheGlobalChannelAndKeepsItsConversationShortcut() {
        presenter.ensureChannels()

        runBlocking { presenter.show(update(isMention = true), previewTextOverride = "hi", shortNpub = { "npub1test" }) }

        val shortcutId = conversationShortcutId("account-a", "group-a")
        val notification = manager.activeNotifications.single().notification
        assertEquals(NotificationChannelSpec.MENTIONS.id, notification.channelId)
        assertEquals(shortcutId, notification.shortcutId)
    }

    @Test
    fun inheritedReactionPostsOnTheGlobalReactionChannel() {
        presenter.ensureChannels()

        runBlocking {
            presenter.show(
                update(isMention = false, reactionEmoji = "👍"),
                shortNpub = { "npub1test" },
            )
        }

        assertEquals(
            NotificationChannelSpec.REACTIONS.id,
            manager.activeNotifications
                .single()
                .notification.channelId,
        )
    }

    @Test
    fun explicitReactionOverridePostsOnTheCustomConversationChannel() {
        presenter.ensureChannels()
        val shortcutId = conversationShortcutId("account-a", "group-a")!!
        ConversationNotificationRouting(context)
            .setScope(
                conversation =
                    NotificationConversationDescriptor(
                        shortcutId = shortcutId,
                        isDm = false,
                        title = "General",
                        primaryVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
                    ),
                category = OverridableConversationNotificationCategory.REACTIONS,
                scope = ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
            ).getOrThrow()

        runBlocking {
            presenter.show(
                update(isMention = false, reactionEmoji = "👍"),
                shortNpub = { "npub1test" },
            )
        }

        assertEquals(
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.REACTIONS.id,
                shortcutId,
            ),
            manager.activeNotifications
                .single()
                .notification.channelId,
        )
    }

    @Test
    fun inheritedAgentActivityPostsOnTheGlobalSilentAgentChannel() {
        presenter.ensureChannels()

        runBlocking {
            presenter.show(
                update(
                    isMention = false,
                    trafficClass = NotificationTrafficClassFfi.AGENT_ACTIVITY,
                ),
                shortNpub = { "npub1test" },
            )
        }

        assertEquals(
            NotificationChannelSpec.AGENT_ACTIVITY.id,
            manager.activeNotifications
                .single()
                .notification.channelId,
        )
    }

    @Test
    fun deletedConversationChannelIsRecreatedWithoutAProcessRestart() {
        presenter.ensureChannels()
        runBlocking {
            presenter.show(
                update(isMention = false),
                previewTextOverride = "first",
                shortNpub = { "npub1test" },
            )
        }
        val shortcutId = conversationShortcutId("account-a", "group-a")!!
        val channelId =
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.GROUP_MESSAGES.id,
                shortcutId,
            )
        assertNotNull(manager.getNotificationChannel(channelId))

        manager.deleteNotificationChannel(channelId)
        assertNull(manager.getNotificationChannel(channelId))
        manager.cancelAll()

        val reposted =
            runBlocking {
                presenter.show(
                    update(isMention = false),
                    previewTextOverride = "second",
                    shortNpub = { "npub1test" },
                )
            }

        assertTrue(reposted)
        assertNotNull(manager.getNotificationChannel(channelId))
        assertEquals(
            channelId,
            manager.activeNotifications
                .single()
                .notification.channelId,
        )
    }

    @Test
    fun senderPersonUsesTheResolvedAvatarBitmap() {
        val content =
            LocalNotificationFormatter.content(
                update = update(isMention = false),
                context = context,
                shortNpub = { "npub1test" },
            )!!
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        assertNotNull(notificationSenderPerson(content, bitmap).icon)
        assertNull(notificationSenderPerson(content, null).icon)
    }

    @Test
    fun conversationShortcutUsesPlainBitmapWithoutAdaptiveSafeZoneCrop() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        val icon = notificationConversationIcon(title = "Alice", seed = "conversation-a", avatarBitmap = bitmap)

        assertEquals(IconCompat.TYPE_BITMAP, icon.type)
    }

    @Test
    fun avatarlessConversationGetsDeterministicDistinctMonogramBitmap() {
        assertEquals("AB", notificationAvatarInitials(" Alice   Baker "))
        assertEquals("AB", notificationAvatarInitials("Alice\u00a0Baker"))
        assertEquals("A", notificationAvatarInitials("Alice"))
        assertEquals("?", notificationAvatarInitials(" \u202e "))
        assertEquals(notificationMonogramBackgroundColor("conversation-a"), notificationMonogramBackgroundColor("conversation-a"))
        assertNotEquals(notificationMonogramBackgroundColor("conversation-a"), notificationMonogramBackgroundColor("conversation-b"))

        val bitmap = notificationMonogramBitmap(title = "Alice Baker", seed = "conversation-a", sizePx = 32)
        val icon = notificationConversationIcon(title = "Alice Baker", seed = "conversation-a", avatarBitmap = null)

        assertEquals(32, bitmap.width)
        assertEquals(32, bitmap.height)
        assertEquals(IconCompat.TYPE_BITMAP, icon.type)
    }

    @Test
    fun freshMessageUsesPresentationTimeNotStaleFfiTimestamp() {
        presenter.ensureChannels()
        val staleFfiTimestampMs = 1_000L
        val beforePostMs = System.currentTimeMillis()

        val posted =
            runBlocking {
                presenter.show(
                    update(isMention = false, timestampMs = staleFfiTimestampMs),
                    previewTextOverride = "hi",
                    shortNpub = { "npub1test" },
                )
            }
        val afterPostMs = System.currentTimeMillis()

        assertTrue(posted)
        val notification = manager.activeNotifications.single().notification
        assertTrue(
            "Notification.when must not use the stale FFI timestamp",
            notification.`when` != staleFfiTimestampMs,
        )
        assertTrue(
            "Notification.when must be sampled at post time",
            notification.`when` in beforePostMs..afterPostMs,
        )
        val newestMessage =
            checkNotNull(
                NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(notification)
                    ?.messages
                    ?.last(),
            )
        assertEquals(
            "New MessagingStyle line must use the same presentation time as Notification.when",
            notification.`when`,
            newestMessage.timestamp,
        )
    }

    @Test
    fun longFirstMessageUsesExpandedTextWithoutLosingConversationActions() {
        presenter.ensureChannels()
        val body = "A long message sentence. ".repeat(12).trim()

        assertTrue(
            runBlocking {
                presenter.show(
                    update(isMention = false),
                    previewTextOverride = body,
                    shortNpub = { "npub1test" },
                )
            },
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(body, notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
        assertEquals(3, notification.actions.size)
        assertEquals(
            context.getString(R.string.reply),
            notification.actions
                .first()
                .title
                .toString(),
        )
        assertEquals(conversationShortcutId("account-a", "group-a"), notification.shortcutId)
    }

    @Test
    fun secondMessageTransitionsExpandedFirstMessageBackToMessagingHistory() {
        presenter.ensureChannels()
        val first = "A long message sentence. ".repeat(12).trim()

        runBlocking {
            presenter.show(
                update(isMention = false, messageIdHex = "first"),
                previewTextOverride = first,
                shortNpub = { "npub1test" },
            )
            presenter.show(
                update(isMention = false, messageIdHex = "second"),
                previewTextOverride = "second",
                shortNpub = { "npub1test" },
            )
        }

        val notification = manager.activeNotifications.single().notification
        assertEquals(
            listOf(first, "second"),
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(notification)
                ?.messages
                ?.map { it.text.toString() },
        )
    }

    @Test
    fun reusedPresenterRestampsPublicVersionOnEachPost() {
        var clockMs = 1_000_000L
        val clockPresenter =
            LocalNotificationPresenter(
                context = context,
                shortcutPublisher = { shortcut ->
                    publishedShortcut = shortcut
                    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
                },
                nowMillis = { clockMs },
            )
        clockPresenter.ensureChannels()

        val firstPresentationMs = clockMs
        assertTrue(
            runBlocking {
                clockPresenter.show(update(isMention = false), previewTextOverride = "first", shortNpub = { "npub1test" })
            },
        )

        clockMs = 5_000_000L
        val secondPresentationMs = clockMs
        assertTrue(
            runBlocking {
                clockPresenter.show(
                    update(isMention = false, messageIdHex = "message-2"),
                    previewTextOverride = "second",
                    shortNpub = { "npub1test" },
                )
            },
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(secondPresentationMs, notification.`when`)
        val publicVersion = checkNotNull(notification.publicVersion)
        assertEquals(
            "publicVersion.when must match the second post's presentation time",
            secondPresentationMs,
            publicVersion.`when`,
        )
        assertNotEquals(
            "publicVersion.when must not retain the first post's cached timestamp",
            firstPresentationMs,
            publicVersion.`when`,
        )
    }

    @Test
    fun bundledHistoryPreservesCarriedMessageTimestamps() {
        presenter.ensureChannels()
        val conversation = LocalNotificationFormatter.conversationDismissalKey("account-a", "group-a")
        val carriedTimestampMs = 2_000_000L
        val staleFfiTimestampMs = 1_000L
        manager.notify(
            conversation.tag,
            conversation.id,
            carriedMessagingNotification("older line" to carriedTimestampMs),
        )
        val beforePostMs = System.currentTimeMillis()

        val posted =
            runBlocking {
                presenter.show(
                    update(isMention = false, timestampMs = staleFfiTimestampMs),
                    previewTextOverride = "new line",
                    shortNpub = { "npub1test" },
                )
            }
        val afterPostMs = System.currentTimeMillis()

        assertTrue(posted)
        val notification = manager.activeNotifications.single().notification
        val messages =
            checkNotNull(
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification),
            ).messages
        assertEquals(2, messages.size)
        assertEquals(carriedTimestampMs, messages.first().timestamp)
        assertTrue(messages.last().timestamp in beforePostMs..afterPostMs)
        assertEquals(notification.`when`, messages.last().timestamp)
    }

    @Test
    fun failedMessagingPostDropsCarriedHistoryAndRetriesOnce() {
        presenter.ensureChannels()
        val conversation = LocalNotificationFormatter.conversationDismissalKey("account-a", "group-a")
        manager.notify(
            conversation.tag,
            conversation.id,
            carriedMessagingNotification("poisoned history" to 2_000_000L),
        )
        var postAttempts = 0
        val recoveringPresenter =
            LocalNotificationPresenter(
                context = context,
                notificationPoster = { notificationManager, tag, id, notification ->
                    postAttempts += 1
                    if (postAttempts == 1) throw RuntimeException("simulated Binder rejection")
                    notificationManager.notify(tag, id, notification)
                },
            )

        val posted =
            runBlocking {
                recoveringPresenter.show(
                    update(isMention = false),
                    previewTextOverride = "safe new line",
                    shortNpub = { "npub1test" },
                )
            }

        assertTrue(posted)
        assertEquals(2, postAttempts)
        val messages =
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(manager.activeNotifications.single().notification)
                ?.messages
                ?.map { it.text.toString() }
        assertEquals(listOf("safe new line"), messages)
    }

    @Test
    fun failedPlainPostIsContainedAndReportedAsNotPosted() {
        presenter.ensureChannels()
        var postAttempts = 0
        val failingPresenter =
            LocalNotificationPresenter(
                context = context,
                notificationPoster = { _, _, _, _ ->
                    postAttempts += 1
                    throw RuntimeException("simulated Binder rejection")
                },
            )

        val posted =
            runBlocking {
                failingPresenter.show(
                    update(isMention = false, reactionEmoji = "thumbs-up"),
                    shortNpub = { "npub1test" },
                )
            }

        assertEquals(1, postAttempts)
        assertTrue(!posted)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun monogramBackgroundMaintainsReadableContrastWithWhiteInitials() {
        val brightestHueSeed = "gk" // Java hash 3300 -> hue 60 (yellow).

        assertTrue(
            ColorUtils.calculateContrast(
                Color.WHITE,
                notificationMonogramBackgroundColor(brightestHueSeed),
            ) >= 4.5,
        )
    }

    private fun carriedMessagingNotification(vararg lines: Pair<String, Long>): Notification {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
        lines.forEach { (text, timestampMs) ->
            style.addMessage(text, timestampMs, Person.Builder().setName("Alice").build())
        }
        return NotificationCompat
            .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setStyle(style)
            .build()
    }

    private fun chatListItem(
        groupId: String,
        name: String,
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

    private fun update(
        isMention: Boolean,
        timestampMs: Long = 1234,
        messageIdHex: String = "message",
        reactionEmoji: String? = null,
        trafficClass: NotificationTrafficClassFfi = NotificationTrafficClassFfi.STANDARD,
        accountRef: String = "account-a",
        trigger: NotificationTriggerFfi = NotificationTriggerFfi.NEW_MESSAGE,
    ) = NotificationUpdateFfi(
        notificationKey = "key",
        conversationKey = "conversation",
        trigger = trigger,
        trafficClass = trafficClass,
        accountRef = accountRef,
        accountIdHex = accountRef,
        groupIdHex = "group-a",
        groupName = "General",
        isDm = false,
        isMention = isMention,
        messageIdHex = messageIdHex,
        sender = user(displayName = "Alice"),
        receiver = user(accountIdHex = "self", displayName = "Me"),
        previewText = "hi",
        reactionEmoji = reactionEmoji,
        reactedToPreview = null,
        timestampMs = timestampMs,
        isFromSelf = false,
    )

    private fun user(
        accountIdHex: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        displayName: String? = null,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )
}
