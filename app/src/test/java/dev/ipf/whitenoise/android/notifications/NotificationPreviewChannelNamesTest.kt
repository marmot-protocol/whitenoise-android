package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.runBlocking
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
class NotificationPreviewChannelNamesTest {
    /** Upgraded installs can scrub and locate a legacy child without creating the retired parent on fresh installs. */
    @Test
    fun retiredAgentChannelsRemainRedactableAndRecognizable() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationChannels.ensureChannels(context)
        assertNull(manager.getNotificationChannel(LEGACY_AGENT_ACTIVITY_CHANNEL_ID))

        val shortcut = checkNotNull(conversationShortcutId("account", "chat"))
        val id = ConversationNotificationChannels.conversationChannelId(LEGACY_AGENT_ACTIVITY_CHANNEL_ID, shortcut)
        manager.createNotificationChannel(NotificationChannel(id, "Private group", NotificationManager.IMPORTANCE_LOW))

        assertTrue(redactNotificationChannelNames(context))
        val legacyChild = manager.getNotificationChannel(id)
        assertEquals(context.getString(R.string.notifications), legacyChild.name)
        assertEquals(context.getString(R.string.notification_hidden_content), legacyChild.description)
        assertEquals(shortcut, conversationChannelShortcutId(legacyChild))
    }

    @Test
    fun offScrubsExistingAndNewChildNamesWithoutChangingTheirAlertSettings() =
        runBlocking {
            val context: Context = RuntimeEnvironment.getApplication()
            val manager = context.getSystemService(NotificationManager::class.java)
            NotificationChannels.ensureChannels(context)
            NotificationPreviewPreferences.setEnabled(context, true, scrub = { true })
            val shortcut = conversationShortcutId("account", "chat")!!
            val id =
                ConversationNotificationChannels.ensureConversationChannel(
                    context,
                    NotificationChannelSpec.GROUP_MESSAGES.id,
                    shortcut,
                    "Private group",
                )!!
            val original = manager.getNotificationChannel(id)
            assertTrue(original.name.toString().contains("Private group"))
            val sound = original.sound
            val vibration = original.vibrationPattern
            val importance = original.importance
            val scrub = suspend { redactNotificationChannelNames(context) }
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false, scrub = scrub))
            val hidden = manager.getNotificationChannel(id)
            assertEquals(NotificationChannels.baseName(context, NotificationChannelSpec.GROUP_MESSAGES), hidden.name)
            assertEquals(context.getString(R.string.notification_hidden_content), hidden.description)
            assertEquals(sound, hidden.sound)
            assertTrue(vibration.contentEquals(hidden.vibrationPattern))
            assertEquals(importance, hidden.importance)
            val nextId =
                ConversationNotificationChannels.ensureConversationChannel(
                    context,
                    NotificationChannelSpec.MENTIONS.id,
                    shortcut,
                    "Another private title",
                )!!
            val next = manager.getNotificationChannel(nextId)
            assertEquals(NotificationChannels.baseName(context, NotificationChannelSpec.MENTIONS), next.name)
            assertEquals(context.getString(R.string.notification_hidden_content), next.description)
        }
}
