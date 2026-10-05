package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationNotificationAndroidStatusTest {
    @Test
    fun readsTheActualCustomTargetWithoutConfusingItWithGlobalDefaults() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("mentions", "Mentions", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(NotificationChannel("custom", "Custom", NotificationManager.IMPORTANCE_NONE))
        val setting = ConversationNotificationCategorySetting(
            NotificationChannelSpec.MENTIONS,
            ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
            true,
            AndroidNotificationSettingsTarget.Conversation("custom", "conversation"),
        )
        assertEquals(
            setOf(NotificationChannelSpec.MENTIONS),
            androidBlockedConversationCategories(context, listOf(setting)),
        )
        val global = setting.copy(
            settingsTarget = AndroidNotificationSettingsTarget.Global(NotificationChannelSpec.MENTIONS),
        )
        assertTrue(androidBlockedConversationCategories(context, listOf(global)).isEmpty())
    }

    @Test
    fun anUncreatedConversationChildUsesItsParentStatus() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("messages_dm", "Messages", NotificationManager.IMPORTANCE_NONE),
        )
        val setting = ConversationNotificationCategorySetting(
            NotificationChannelSpec.DIRECT_MESSAGES,
            ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT,
            false,
            AndroidNotificationSettingsTarget.Conversation("uncreated", "conversation"),
        )
        assertEquals(
            setOf(NotificationChannelSpec.DIRECT_MESSAGES),
            androidBlockedConversationCategories(context, listOf(setting)),
        )
    }
}
