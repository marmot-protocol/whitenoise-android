package dev.ipf.whitenoise.android.state

import android.Manifest
import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenKeyRule
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A Firebase auto-init write that fails leaves the durable registration sync pending (#4081), and no sync
 * acknowledges it until a later write succeeds, so a change never reports success with auto-init left wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FcmAutoInitWriteFailureTest {
    @get:Rule
    val pushTokenKey = PushTokenKeyRule()

    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    /** Starts every test on the persistent connection with the notification permission granted. */
    @Before
    fun resetDeliveryState() {
        BackgroundConnectionPreferences.setEnabledDurably(context, true)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Stops the foreground service a test may have started. */
    @After
    fun stopTestService() {
        NotificationStreamForegroundService.stop(context)
    }

    /** A sync whose off write fails keeps the pending sync, and the next sync retries the write and clears it. */
    @Test
    fun failedOffWriteKeepsPendingSyncUntilARetrySucceeds() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform)
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()
                platform.failAutoInitWrites = true

                assertFalse(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })

                assertTrue("a failed write must leave the sync pending", store.nativePushRegistrationSyncPending())
                platform.failAutoInitWrites = false

                assertTrue(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })

                assertEquals(false, platform.autoInitWrites.lastOrNull())
                assertFalse("the successful retry acknowledges it", store.nativePushRegistrationSyncPending())
            } finally {
                platform.failAutoInitWrites = false
                fixture.close()
            }
        }

    /**
     * A committed Push cutover whose on write fails still succeeds but leaves the sync pending, and the next
     * sync turns auto-init on and clears it.
     */
    @Test
    fun failedOnWriteAfterCommittedCutoverKeepsPendingSync() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform)
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()
                platform.failAutoInitWrites = true

                assertTrue(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                    },
                )

                assertTrue("the cutover must register", fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue("a failed write must leave the sync pending", store.nativePushRegistrationSyncPending())
                platform.failAutoInitWrites = false

                assertTrue(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })

                assertEquals(true, platform.autoInitWrites.lastOrNull())
                assertFalse("the successful retry acknowledges it", store.nativePushRegistrationSyncPending())
            } finally {
                platform.failAutoInitWrites = false
                fixture.close()
            }
        }

    /** Builds two local-signing accounts, the active one off native push, behind [platform]. */
    private fun fixture(platform: RecordingNativePushFallbackPlatform) =
        NotificationBootstrapTestFixture(
            context = context,
            accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
            initialNotificationSettings = NotificationSettingsFfi(ACCOUNT_A, "$ACCOUNT_A-id", true, false),
            nativePushFallbackPlatform = platform,
            pushServerConfigProvider = { CONFIG },
            fcmTokenReader = { TOKEN },
            nativePushCapabilityResolver = { NativePushCapability.Available },
        )

    /** Builds a running local-signing account summary for the given ref. */
    private fun account(ref: String) = AccountSummaryFfi(ref, "$ref-id", true, false, false, true, false)

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val TOKEN = "test-token"
        val CONFIG = PushServerConfig("0".repeat(64), null)
    }
}
