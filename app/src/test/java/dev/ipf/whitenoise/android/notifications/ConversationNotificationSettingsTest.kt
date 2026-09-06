package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationNotificationSettingsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Test
    fun conversationShortcutIdIsStableAndScopedToAccountAndGroup() {
        val shortcutId = conversationShortcutId("account-a", "group-a")

        assertEquals(shortcutId, conversationShortcutId("account-a", "group-a"))
        assertNotEquals(shortcutId, conversationShortcutId("account-b", "group-a"))
        assertNotEquals(shortcutId, conversationShortcutId("account-a", "group-b"))
        assertNull(conversationShortcutId("", "group-a"))
        assertNull(conversationShortcutId("account-a", "  "))
    }

    @Test
    fun conversationShortcutAccountScopeIsStableAndDoesNotExposeTheAccountRef() {
        val scope = conversationShortcutAccountScope("account-a")

        assertEquals(scope, conversationShortcutAccountScope("account-a"))
        assertNotEquals(scope, conversationShortcutAccountScope("account-b"))
        assertNotEquals("account-a", scope)
        assertNull(conversationShortcutAccountScope(" "))
    }

    @Test
    fun globalIntentTargetsTheExactChannelWithoutConversationScope() {
        val intent = notificationChannelSettingsIntent(context, NotificationChannelSpec.REACTIONS.id)

        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(NotificationChannelSpec.REACTIONS.id, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertNull(intent.getStringExtra(Settings.EXTRA_CONVERSATION_ID))
    }

    @Test
    fun api30IntentTargetsTheConversationShortcut() {
        val intent =
            conversationNotificationSettingsIntent(
                context = context,
                accountRef = "account-a",
                groupIdHex = "group-a",
                channelId = NotificationChannelSpec.GROUP_MESSAGES.id,
                sdkInt = 30,
            )

        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(NotificationChannelSpec.GROUP_MESSAGES.id, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(
            conversationShortcutId("account-a", "group-a"),
            intent.getStringExtra(Settings.EXTRA_CONVERSATION_ID),
        )
    }

    @Test
    fun blankChannelFallsBackToAppNotificationSettings() {
        val intent =
            conversationNotificationSettingsIntent(
                context = context,
                accountRef = "account-a",
                groupIdHex = "group-a",
                channelId = " ",
                sdkInt = 30,
            )

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertNull(intent.getStringExtra(Settings.EXTRA_CONVERSATION_ID))
    }

    @Test
    fun preApi30IntentFallsBackToAppNotificationSettings() {
        val intent =
            conversationNotificationSettingsIntent(
                context = context,
                accountRef = "account-a",
                groupIdHex = "group-a",
                channelId = NotificationChannelSpec.GROUP_MESSAGES.id,
                sdkInt = 29,
            )

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    /** Group preparation creates only the required message child and opens its exact ids. */
    @Test
    fun openDeepLinksWithTheGroupParentAndCreatesOnlyItsMessageChannel() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        NotificationChannels.ensureChannels(app)

        prepareAndOpenConversationNotificationSettings(
            app,
            accountRef = "account-a",
            groupIdHex = "group-a",
            isDm = false,
        )

        val shortcutId = conversationShortcutId("account-a", "group-a")
        val expectedChannelId = ConversationNotificationChannels.conversationChannelId(NotificationChannelSpec.GROUP_MESSAGES.id, shortcutId!!)
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
        assertEquals(expectedChannelId, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(shortcutId, started.getStringExtra(Settings.EXTRA_CONVERSATION_ID))
        assertNotNull(manager.getNotificationChannel(expectedChannelId))
        assertNull(
            manager.getNotificationChannel(
                ConversationNotificationChannels.conversationChannelId(
                    NotificationChannelSpec.MENTIONS.id,
                    shortcutId,
                ),
            ),
        )
    }

    /** DM preparation selects the direct-message parent and preserves its display title. */
    @Test
    fun openDeepLinksToTheDmConversationChannelForDms() {
        val app = RuntimeEnvironment.getApplication()
        NotificationChannels.ensureChannels(app)

        prepareAndOpenConversationNotificationSettings(
            app,
            accountRef = "account-a",
            groupIdHex = "group-a",
            isDm = true,
            conversationTitle = "Green Orca",
        )

        val shortcutId = conversationShortcutId("account-a", "group-a")!!
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.DIRECT_MESSAGES.id,
                shortcutId,
            ),
            started.getStringExtra(Settings.EXTRA_CHANNEL_ID),
        )
        val conversationChannelId =
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.DIRECT_MESSAGES.id,
                shortcutId,
            )
        assertEquals(
            "Green Orca · Direct messages",
            app
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(conversationChannelId)
                .name
                .toString(),
        )
        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(app).single { it.id == shortcutId }
        assertEquals("Green Orca", shortcut.longLabel.toString())
        assertEquals(
            conversationShortcutAccountScope("account-a"),
            shortcut.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA),
        )
        assertNotNull(
            conversationSettingsShortcut(
                context = app,
                shortcutId = shortcutId,
                accountRef = "account-a",
                groupIdHex = "group-a",
                title = "Green Orca",
                avatarUrl = null,
            ).icon,
        )
    }

    /** Optional custom categories can resolve their own exact conversation child. */
    @Test
    fun openCanTargetTheAgentActivityChannelForOneConversation() {
        val app = RuntimeEnvironment.getApplication()
        NotificationChannels.ensureChannels(app)

        prepareAndOpenConversationNotificationSettings(
            app,
            accountRef = "account-a",
            groupIdHex = "group-a",
            isDm = false,
            parent = NotificationChannelSpec.AGENT_ACTIVITY,
        )

        val shortcutId = conversationShortcutId("account-a", "group-a")!!
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.AGENT_ACTIVITY.id,
                shortcutId,
            ),
            started.getStringExtra(Settings.EXTRA_CHANNEL_ID),
        )
        assertNotNull(
            app
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(
                    ConversationNotificationChannels.conversationChannelId(
                        NotificationChannelSpec.AGENT_ACTIVITY.id,
                        shortcutId,
                    ),
                ),
        )
    }

    /** Primary preparation targets the active channel version for custom vibration. */
    @Test
    fun primarySettingsDeepLinkTargetsTheSelectedVibrationChannelVersion() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        NotificationChannels.ensureChannels(app)

        prepareAndOpenConversationNotificationSettings(
            context = app,
            accountRef = "account-vibration",
            groupIdHex = "group-vibration",
            isDm = false,
            primaryVibrationPattern = ConversationVibrationPattern.DOUBLE,
        )

        val shortcutId = conversationShortcutId("account-vibration", "group-vibration")!!
        val activeId =
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.GROUP_MESSAGES.id,
                shortcutId,
                ConversationVibrationPattern.DOUBLE,
            )
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(activeId, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(shortcutId, started.getStringExtra(Settings.EXTRA_CONVERSATION_ID))
        assertNotNull(manager.getNotificationChannel(activeId))
    }

    @Test
    fun settingsShortcutRefreshReplacesAStaleIntentWithDirectConversationNavigation() {
        val existingIntent = Intent("dev.ipf.whitenoise.TEST_DIRECT_CHAT")
        val existing =
            ShortcutInfoCompat
                .Builder(context, "conversation-existing")
                .setShortLabel("npub1old")
                .setLongLabel("npub1old")
                .setIntent(existingIntent)
                .setLongLived(true)
                .build()

        val refreshed =
            conversationSettingsShortcut(
                context = context,
                shortcutId = "conversation-existing",
                accountRef = "account-a",
                groupIdHex = "group-a",
                title = "Green Orca",
                avatarUrl = null,
                existing = existing,
            )

        assertEquals("Green Orca", refreshed.longLabel.toString())
        val refreshedIntent = refreshed.intents.single()
        assertEquals(NotificationNavigation.ACTION_OPEN, refreshedIntent.action)
        assertEquals(
            NotificationTarget("account-a", "group-a", null, NotificationTargetKind.MESSAGE),
            NotificationNavigation.parse(refreshedIntent) { key, token ->
                NotificationTapTokens.create(context).isValid(key, token)
            },
        )
    }

    /** Repeat preparation keeps the previously resolved name instead of an npub fallback. */
    @Test
    fun openingSettingsDoesNotDowngradeAResolvedChannelNameToNpub() {
        val app = RuntimeEnvironment.getApplication()
        NotificationChannels.ensureChannels(app)
        val shortcutId = conversationShortcutId("account-resolved", "group-resolved")!!
        ShortcutManagerCompat.pushDynamicShortcut(
            app,
            conversationSettingsShortcut(
                context = app,
                shortcutId = shortcutId,
                accountRef = "account-resolved",
                groupIdHex = "group-resolved",
                title = "Green Orca",
                avatarUrl = null,
            ),
        )

        prepareAndOpenConversationNotificationSettings(
            context = app,
            accountRef = "account-resolved",
            groupIdHex = "group-resolved",
            isDm = true,
            conversationTitle = "npub1jc3ut...hsq6nt96",
        )

        val channelId =
            ConversationNotificationChannels.conversationChannelId(
                NotificationChannelSpec.DIRECT_MESSAGES.id,
                shortcutId,
            )
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId)
        assertEquals("Green Orca · Direct messages", channel.name.toString())
    }

    /** Runs the same prepare-then-pure-launch boundary used by the settings screen. */
    private fun prepareAndOpenConversationNotificationSettings(
        context: Context,
        accountRef: String,
        groupIdHex: String,
        isDm: Boolean,
        parent: NotificationChannelSpec? = null,
        conversationTitle: String? = null,
        conversationAvatarUrl: String? = null,
        primaryVibrationPattern: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
    ): ConversationNotificationSettingsLaunchAttempt =
        runBlocking {
            val targetParent = parent ?: ConversationNotificationChannels.primaryMessageParent(isDm)
            val preparation =
                ConversationNotificationSettingsPreparer(dispatcher = Dispatchers.Unconfined).prepare(
                    context = context,
                    request =
                        ConversationNotificationSettingsPreparationRequest(
                            accountRef = accountRef,
                            groupIdHex = groupIdHex,
                            isDm = isDm,
                            conversationTitle = conversationTitle ?: "Test conversation",
                            conversationAvatarUrl = conversationAvatarUrl,
                            primaryVibrationPattern = primaryVibrationPattern,
                            requestedParents = listOf(targetParent),
                        ),
                ) as ConversationNotificationSettingsPreparation.Ready
            openPreparedConversationNotificationSettings(
                context = context,
                target = checkNotNull(preparation.targetsByParentChannelId[targetParent.id]),
            )
        }
}
