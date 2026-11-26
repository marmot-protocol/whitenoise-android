package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import android.provider.Settings
import dev.ipf.whitenoise.android.notifications.NotificationChannels
import dev.ipf.whitenoise.android.notifications.ProfileNotificationChannels
import dev.ipf.whitenoise.android.notifications.ProfileNotificationMode
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverridePreferences
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup
import dev.ipf.whitenoise.android.notifications.clearRevokedConversationShortcutsForAccount
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    /** Account channel cleanup before queued customization starts rejects the stale customization cleanly. */
    @Test fun cleanupBeforeQueuedProfileCustomizationRejectsChannelCreation() =
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
            clearRevokedConversationShortcutsForAccount(context, "personal", includeUnscopedLegacy = false)
            advanceUntilIdle()
            assertEquals(ProfileNotificationMode.DEFAULT, preferences.get("personal", author).mode)
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertEquals(
                0,
                context.getSystemService(android.app.NotificationManager::class.java).notificationChannels.count {
                    it.id.startsWith(ProfileNotificationChannels.PREFIX)
                },
            )
            assertFalse(controller.state.value.busy)
            assertFalse(controller.state.value.failed)
        }

    /**
     * Even after `change()` passes its initial `check(writeAllowed())`, account channel cleanup winning
     * `mutationLock` prevents `ProfileNotificationChannels.customize` from creating a stale channel.
     */
    @Test fun cleanupWinningMutationLockRejectsInFlightProfileCustomization() =
        runTest {
            NotificationChannels.ensureChannels(context)
            val preferences = ProfileNotificationOverridePreferences(context)
            val ioThread = AtomicReference<Thread?>()
            val ioExecutor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "profile-override-mutation-lock-test").also(ioThread::set)
                }
            val ioDispatcher = ioExecutor.asCoroutineDispatcher()
            val ownerChecks = AtomicInteger(0)
            val passedInitialCheck = CountDownLatch(1)
            val controller =
                ProfileNotificationOverrideController(
                    context,
                    preferences,
                    "personal",
                    author,
                    this,
                    {
                        if (ownerChecks.incrementAndGet() == 2) passedInitialCheck.countDown()
                        true
                    },
                    ioDispatcher,
                )
            try {
                synchronized(UserEventNotificationGroup.mutationLock) {
                    controller.customize("Alice")
                    runCurrent()
                    assertTrue(passedInitialCheck.await(2, TimeUnit.SECONDS))
                    val worker = checkNotNull(ioThread.get())
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                        Thread.sleep(5)
                    }
                    assertEquals(Thread.State.BLOCKED, worker.state)
                    clearRevokedConversationShortcutsForAccount(context, "personal", includeUnscopedLegacy = false)
                }
                ioExecutor.submit {}.get(2, TimeUnit.SECONDS)
                advanceUntilIdle()
                assertTrue(ownerChecks.get() >= 3)
                assertEquals(ProfileNotificationMode.DEFAULT, preferences.get("personal", author).mode)
                assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
                assertEquals(
                    0,
                    context.getSystemService(android.app.NotificationManager::class.java).notificationChannels.count {
                        it.id.startsWith(ProfileNotificationChannels.PREFIX)
                    },
                )
                assertFalse(controller.state.value.busy)
                assertFalse(controller.state.value.failed)
            } finally {
                ioDispatcher.close()
                ioExecutor.shutdownNow()
            }
        }
}
