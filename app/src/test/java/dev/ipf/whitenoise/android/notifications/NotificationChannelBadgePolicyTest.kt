package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationChannelBadgePolicyTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun frameworkChannelsOnlyAllowBadgesForUnreadMessageAttention() {
        deleteOrdinaryChannels()

        NotificationChannels.ensureChannels(context)

        NotificationChannelSpec.entries.forEach { spec ->
            assertEquals(
                "${spec.name} launcher badge policy",
                spec.launcherBadgeByDefault,
                manager.getNotificationChannel(spec.id).canShowBadge(),
            )
        }
    }

    @Test
    fun conversationChildrenInheritTheirParentBadgePolicy() {
        deleteOrdinaryChannels()
        NotificationChannels.ensureChannels(context)
        val shortcutId = "badge-policy-conversation"

        // Only the ordinary-message child is created eagerly, the other event
        // children are provisioned lazily — go through the single-parent entry
        // point so every child exercises the real clone-from-parent path.
        ConversationNotificationChannels.relevantParents(isDm = false).forEach { parent ->
            val childId =
                ConversationNotificationChannels.ensureConversationChannel(
                    context = context,
                    parentChannelId = parent.id,
                    conversationShortcutId = shortcutId,
                )
            assertNotNull("${parent.name} child channel id", childId)
            assertEquals(
                "${parent.name} child launcher badge policy",
                parent.launcherBadgeByDefault,
                manager.getNotificationChannel(childId).canShowBadge(),
            )
        }
    }

    @Test
    fun reEnsuringChannelsPreservesExistingBadgeAndAlertChoices() {
        deleteOrdinaryChannels()
        val customSound = Uri.parse("content://test/user-notification-sound")
        val audioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
        val userCustomizedUpdates =
            NotificationChannel(
                NotificationChannelSpec.APP_UPDATES.id,
                "Existing updates",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 321L)
                setSound(customSound, audioAttributes)
            }
        val userCustomizedMessages =
            NotificationChannel(
                NotificationChannelSpec.DIRECT_MESSAGES.id,
                "Existing messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
            }
        manager.createNotificationChannel(userCustomizedUpdates)
        manager.createNotificationChannel(userCustomizedMessages)

        NotificationChannels.ensureChannels(context)

        val updates = manager.getNotificationChannel(NotificationChannelSpec.APP_UPDATES.id)
        assertTrue(updates.canShowBadge())
        assertTrue(updates.shouldVibrate())
        assertEquals(listOf(0L, 321L), updates.vibrationPattern!!.toList())
        assertEquals(customSound, updates.sound)
        val messages = manager.getNotificationChannel(NotificationChannelSpec.DIRECT_MESSAGES.id)
        assertFalse(messages.canShowBadge())
        assertFalse(messages.shouldVibrate())
    }

    @Test
    fun persistentBackgroundConnectionChannelNeverAllowsLauncherBadges() {
        BackgroundConnectionNotification.build(context)

        assertFalse(
            manager
                .getNotificationChannel(BackgroundConnectionNotification.CHANNEL_ID)
                .canShowBadge(),
        )
    }

    private fun deleteOrdinaryChannels() {
        NotificationChannelSpec.entries.forEach { manager.deleteNotificationChannel(it.id) }
    }
}
