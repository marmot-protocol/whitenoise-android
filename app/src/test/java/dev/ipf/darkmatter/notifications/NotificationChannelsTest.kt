package dev.ipf.darkmatter.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationChannelsTest {
    @Test
    fun channelStatesReadLiveOsImportance() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelSpec.REACTIONS.id,
                "Reactions",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        val states = NotificationChannels.channelStates(context)

        assertFalse(states.first { it.spec == NotificationChannelSpec.REACTIONS }.enabled)
        assertTrue(states.first { it.spec == NotificationChannelSpec.DIRECT_MESSAGES }.enabled)
    }

    @Test
    fun setChannelEnabledMutesAndRequiresSystemSettingsToUnmute() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannels.ensureChannels(context)

        assertTrue(NotificationChannels.setChannelEnabled(context, NotificationChannelSpec.DIRECT_MESSAGES, false))
        val muted = manager.getNotificationChannel(NotificationChannelSpec.DIRECT_MESSAGES.id)
        assertEquals(NotificationManager.IMPORTANCE_NONE, muted.importance)
        assertFalse(
            NotificationChannels
                .channelStates(context)
                .first { it.spec == NotificationChannelSpec.DIRECT_MESSAGES }
                .enabled,
        )

        assertFalse(NotificationChannels.setChannelEnabled(context, NotificationChannelSpec.DIRECT_MESSAGES, true))
        assertEquals(
            NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(NotificationChannelSpec.DIRECT_MESSAGES.id).importance,
        )
        assertFalse(
            NotificationChannels
                .channelStates(context)
                .first { it.spec == NotificationChannelSpec.DIRECT_MESSAGES }
                .enabled,
        )
    }
}
