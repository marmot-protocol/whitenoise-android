package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual Android channel inventory proves creation bounds and preservation of user-owned alert behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileNotificationChannelsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val author = "a".repeat(64)
    private val default = ConversationVibrationPattern.SYSTEM_DEFAULT

    @Test fun viewingAndResetNeverCreateChannelsAndExplicitVersionsAreBounded() {
        val channels = ProfileNotificationChannels(context)
        val store = ProfileNotificationOverridePreferences(context)
        NotificationChannels.ensureChannels(context)
        repeat(8) { assertNull(channels.existing("personal", author, default, "Alice")) }
        assertEquals(0, profileCount())
        repeat(3) {
            ConversationVibrationPattern.entries.forEach { pattern ->
                channels.customize("personal", author, "Alice", pattern, default)
            }
        }
        assertEquals(4, profileCount())
        store.set("personal", author, ProfileNotificationOverride(ProfileNotificationMode.CUSTOM))
        store.set("personal", author, ProfileNotificationOverride())
        store.clearAccount("personal")
        assertEquals(4, profileCount())
    }

    @Test fun renameAndPrivacyPreserveIdAndAndroidAlertSettings() {
        NotificationChannels.ensureChannels(context)
        val channels = ProfileNotificationChannels(context)
        val id = channels.customize("personal", author, "Alice", default, default)
        val saved = manager.getNotificationChannel(id)
        saved.importance = NotificationManager.IMPORTANCE_LOW
        saved.setSound(Uri.parse("content://sounds/custom"), saved.audioAttributes)
        manager.createNotificationChannel(saved)
        val before = manager.getNotificationChannel(id)
        assertEquals(id, channels.existing("personal", author, default, "New nickname"))
        assertTrue(manager.getNotificationChannel(id).name.contains("New nickname"))
        assertEquals(before.sound, manager.getNotificationChannel(id).sound)
        assertEquals(before.importance, manager.getNotificationChannel(id).importance)
        assertEquals(id, channels.existing("personal", author, default, "Private name", redact = true))
        assertFalse(manager.getNotificationChannel(id).name.contains("Private name"))
        assertTrue(redactNotificationChannelNames(context))
        assertEquals(before.sound, manager.getNotificationChannel(id).sound)
        assertEquals(1, profileCount())
    }

    @Test fun distinctAccountsAndAuthorsHaveDistinctBoundedIdsAndNoGroupDimension() {
        val id = checkNotNull(ProfileNotificationChannels.id("personal", author, default))
        assertNotEquals(id, ProfileNotificationChannels.id("work", author, default))
        assertNotEquals(id, ProfileNotificationChannels.id("personal", "b".repeat(64), default))
        assertTrue(id.length < 200)
        assertFalse(id.contains(author))
        assertFalse(id.contains("personal"))
    }

    @Test fun creatingANewPatternCopiesThePreviousSoundAndLeavesOldChannelUntouched() {
        NotificationChannels.ensureChannels(context)
        val channels = ProfileNotificationChannels(context)
        val old = channels.customize("personal", author, "Alice", default, default)
        val source = manager.getNotificationChannel(old)
        source.setSound(Uri.parse("content://sounds/custom"), source.audioAttributes)
        manager.createNotificationChannel(source)
        val selected = channels.customize("personal", author, "Alice", ConversationVibrationPattern.DOUBLE, default)
        assertNotEquals(old, selected)
        assertEquals(manager.getNotificationChannel(old).sound, manager.getNotificationChannel(selected).sound)
        assertNotNull(manager.getNotificationChannel(old))
        assertEquals(
            ConversationVibrationPattern.DOUBLE,
            channels
                .effectiveVibration(
                    "personal",
                    author,
                    ConversationVibrationPattern.DOUBLE,
                ).pattern,
        )
    }

    private fun profileCount(): Int {
        val channels = manager.notificationChannels
        return channels.count { it.id.startsWith(ProfileNotificationChannels.PREFIX) }
    }
}
