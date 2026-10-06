package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import android.provider.Settings
import dev.ipf.whitenoise.android.notifications.NotificationChannels
import dev.ipf.whitenoise.android.notifications.ProfileNotificationChannels
import dev.ipf.whitenoise.android.notifications.ProfileNotificationMode
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverridePreferences
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/** Controlled IO proves the screen owner cannot write or launch after account change/disposal. */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileNotificationOverrideControllerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val author = "a".repeat(64)

    @Test fun explicitCustomizationOpensTheExactPersistedChannelAndResetKeepsIt() =
        runTest {
            NotificationChannels.ensureChannels(context)
            val preferences = ProfileNotificationOverridePreferences(context)
            val controller =
                ProfileNotificationOverrideController(
                    context,
                    preferences,
                    "personal",
                    author,
                    this,
                    { true },
                    StandardTestDispatcher(testScheduler),
                )
            controller.customize("Alice")
            advanceUntilIdle()
            val selection = preferences.get("personal", author)
            assertEquals(ProfileNotificationMode.CUSTOM, selection.mode)
            val intent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
            val id = ProfileNotificationChannels.id("personal", author, selection.vibration)
            assertEquals(id, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            controller.selectMode(ProfileNotificationMode.DEFAULT)
            advanceUntilIdle()
            assertEquals(ProfileNotificationMode.DEFAULT, preferences.get("personal", author).mode)
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            assertNotNull(manager.getNotificationChannel(id))
            assertFalse(controller.state.value.busy)
        }

    /**
     * Suspended UI actions lose authority after account replacement or route disposal, even if their coroutine
     * resumes.
     */
    @Test fun lateAccountAndDisposedOwnersDoNotCreateChannelsOrWritePreferences() =
        runTest {
            NotificationChannels.ensureChannels(context)
            val preferences = ProfileNotificationOverridePreferences(context)
            var current = true
            val controller =
                ProfileNotificationOverrideController(
                    context,
                    preferences,
                    "personal",
                    author,
                    this,
                    { current },
                    StandardTestDispatcher(testScheduler),
                )
            controller.customize("Alice")
            current = false
            advanceUntilIdle()
            assertEquals(ProfileNotificationMode.DEFAULT, preferences.get("personal", author).mode)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            current = true
            controller.dispose()
            controller.selectMode(ProfileNotificationMode.MUTED)
            advanceUntilIdle()
            assertEquals(ProfileNotificationMode.DEFAULT, preferences.get("personal", author).mode)
            assertEquals(
                0,
                context.getSystemService(android.app.NotificationManager::class.java).notificationChannels.count {
                    it.id.startsWith(ProfileNotificationChannels.PREFIX)
                },
            )
        }
}
