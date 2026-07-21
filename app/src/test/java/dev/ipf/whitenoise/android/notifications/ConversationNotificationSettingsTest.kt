package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
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

    @Test
    fun openDeepLinksToTheGroupConversationChannelNotItsParent() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        NotificationChannels.ensureChannels(app)

        openConversationNotificationSettings(app, accountRef = "account-a", groupIdHex = "group-a", isDm = false)

        val shortcutId = conversationShortcutId("account-a", "group-a")
        val expectedChannelId = ConversationNotificationChannels.conversationChannelId(NotificationChannelSpec.GROUP_MESSAGES.id, shortcutId!!)
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
        assertEquals(expectedChannelId, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        // Explicitly NOT the bare parent channel.
        assertNotEquals(NotificationChannelSpec.GROUP_MESSAGES.id, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(shortcutId, started.getStringExtra(Settings.EXTRA_CONVERSATION_ID))
        // Multi-parent: both the message and mention conversation channels were created.
        assertNotNull(manager.getNotificationChannel(expectedChannelId))
        assertNotNull(manager.getNotificationChannel(ConversationNotificationChannels.conversationChannelId(NotificationChannelSpec.MENTIONS.id, shortcutId)))
    }

    @Test
    fun openDeepLinksToTheDmConversationChannelForDms() {
        val app = RuntimeEnvironment.getApplication()
        NotificationChannels.ensureChannels(app)

        openConversationNotificationSettings(app, accountRef = "account-a", groupIdHex = "group-a", isDm = true)

        val shortcutId = conversationShortcutId("account-a", "group-a")!!
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(
            ConversationNotificationChannels.conversationChannelId(NotificationChannelSpec.DIRECT_MESSAGES.id, shortcutId),
            started.getStringExtra(Settings.EXTRA_CHANNEL_ID),
        )
        assertNotEquals(NotificationChannelSpec.DIRECT_MESSAGES.id, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun openCanTargetTheAgentActivityChannelForOneConversation() {
        val app = RuntimeEnvironment.getApplication()
        NotificationChannels.ensureChannels(app)

        openConversationNotificationSettings(
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
    }
}
