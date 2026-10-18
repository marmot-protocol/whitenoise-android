package dev.ipf.whitenoise.android.state

import android.Manifest
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenKeyRule
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
class PushTokenRefreshIntegrationTest {
    @get:Rule
    val pushTokenKey = PushTokenKeyRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun prepareDelivery() {
        BackgroundConnectionPreferences.setEnabledDurably(context, false)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun currentSdkTokenReplacesCachedTokenBeforeRegistration() =
        runBlocking {
            var sdkToken: String? = null
            val fixture = fixture { sdkToken }
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("old-token")
                sdkToken = "current-token"

                assertTrue(fixture.appState.syncNativePushRegistrationIfEnabled())

                assertEquals("current-token", PushTokenStore.create(context).lastToken())
                assertTrue(fixture.upsertedPushTokens.isNotEmpty())
                assertTrue(fixture.upsertedPushTokens.all { it == "current-token" })
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    @Test
    fun missingOrFailedAcquisitionNeverRegistersCachedToken() =
        runBlocking {
            val readers: List<suspend () -> String?> = listOf({ null }, { " " }, { error("unavailable") })
            for (reader in readers) {
                val fixture = fixture(reader = reader)
                try {
                    fixture.bootstrap()
                    PushTokenStore.create(context).setToken("old-token")

                    assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                    assertTrue(fixture.upsertedPushTokens.isEmpty())
                    assertEquals("old-token", PushTokenStore.create(context).lastToken())
                } finally {
                    fixture.close()
                    NotificationStreamForegroundService.stop(context)
                }
            }
        }

    @Test
    fun unavailableCapabilityNeverAcquiresToken() =
        runBlocking {
            var reads = 0
            val fixture =
                fixture(available = false) {
                    reads += 1
                    "current-token"
                }
            try {
                fixture.bootstrap()
                fixture.appState.syncNativePushRegistrationIfEnabled()
                assertEquals(0, reads)
                assertTrue(fixture.upsertedPushTokens.isEmpty())
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** Replayed cleanup requires confirmed disabled settings while another account can register. */
    @Test
    fun replayedDisableRemainsPendingUntilNativeConfirmsAndDoesNotDisableAnotherAccount() =
        runBlocking {
            val store = PushTokenStore.create(context)
            assertTrue(store.recordPendingDisable("account-a"))
            val registered = java.util.concurrent.CopyOnWriteArrayList<String>()
            var confirmDisable = false
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts =
                        listOf(
                            AccountSummaryFfi("account-a", "id-a", true, false, false, true),
                            AccountSummaryFfi("account-b", "id-b", true, false, false, true),
                        ),
                    initialNotificationSettings = NotificationSettingsFfi("account-a", "id-a", true, true),
                    emitStartupNotification = false,
                    onSetNativePushEnabled = { account, _ ->
                        NotificationSettingsFfi(account, "id-a", true, !confirmDisable)
                    },
                    onUpsertPushRegistration = { registered += it },
                    nativePushFallbackPlatform = RecordingNativePushFallbackPlatform(context),
                    pushServerConfigProvider = { PushServerConfig("0".repeat(64), null) },
                    nativePushCapabilityResolver = { NativePushCapability.Available },
                    fcmTokenReader = { "current-token" },
                )
            fixture.replaceNotificationSettings(NotificationSettingsFfi("account-b", "id-b", true, true))
            try {
                fixture.bootstrap()
                fixture.appState.syncNativePushRegistrationIfEnabled()
                assertEquals(setOf("account-a"), PushTokenStore.create(context).pendingDisables())
                assertFalse("account-a" in registered)
                assertTrue("account-b" in registered)
                assertTrue(fixture.notificationSettings("account-b").nativePushEnabled)
                confirmDisable = true
                fixture.appState.syncNativePushRegistrationIfEnabled()
                assertTrue(PushTokenStore.create(context).pendingDisables().isEmpty())
                assertFalse(fixture.notificationSettings("account-a").nativePushEnabled)
                assertTrue(fixture.notificationSettings("account-b").nativePushEnabled)
                assertFalse("account-a" in registered)
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** A persisted token without a pending marker is retried at the next runtime-start boundary. */
    @Test
    fun tokenWithMissingMarkerIsRetriedAtRuntimeStart() =
        runBlocking {
            var token = "first-token"
            val fixture = fixture { token }
            try {
                fixture.bootstrap()
                fixture.appState.syncNativePushRegistrationIfEnabled()
                fixture.upsertedPushTokens.clear()
                token = "rotated-token"
                val store = PushTokenStore.create(context)
                store.setToken(token)
                assertFalse(store.nativePushRegistrationSyncPending())
                fixture.ensureNotificationRuntimeStarted()
                assertTrue("rotated-token" in fixture.upsertedPushTokens)
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** Retry waits behind a delivery transaction instead of upserting in the middle of its disable work. */
    @Test
    fun storageRetryWaitsForDeliveryModeOwnership() =
        runBlocking {
            var token = "first-token"
            val fixture = fixture { token }
            try {
                fixture.bootstrap()
                fixture.appState.syncNativePushRegistrationIfEnabled()
                fixture.upsertedPushTokens.clear()
                token = "second-token"
                val modeMutex =
                    WhiteNoiseAppState::class.java
                        .getDeclaredField("notificationDeliveryModeMutex")
                        .apply { isAccessible = true }
                        .get(fixture.appState) as kotlinx.coroutines.sync.Mutex
                modeMutex.lock()
                var ownsModeLock = true
                try {
                    fixture.runWithMainLooperPumping {
                        kotlinx.coroutines.coroutineScope {
                            val retry = async { fixture.appState.retryPushStorageRecovery() }
                            kotlinx.coroutines.withTimeout(5_000L) {
                                while (!fixture.appState.pushStorageRecoveryBusy) kotlinx.coroutines.delay(5L)
                            }
                            kotlinx.coroutines.delay(50L)
                            assertFalse(retry.isCompleted)
                            assertTrue(fixture.upsertedPushTokens.isEmpty())
                            modeMutex.unlock()
                            ownsModeLock = false
                            retry.await()
                        }
                    }
                } finally {
                    if (ownsModeLock) modeMutex.unlock()
                }
                assertTrue("second-token" in fixture.upsertedPushTokens)
                assertFalse(fixture.appState.pushStorageRecoveryBusy)
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    private fun fixture(
        available: Boolean = true,
        reader: suspend () -> String?,
    ) = NotificationBootstrapTestFixture(
        context = context,
        accounts = listOf(AccountSummaryFfi("account-a", "account-id", true, false, false, true)),
        initialNotificationSettings = NotificationSettingsFfi("account-a", "account-id", true, true),
        nativePushFallbackPlatform = RecordingNativePushFallbackPlatform(context),
        pushServerConfigProvider = { PushServerConfig("0".repeat(64), null) },
        nativePushCapabilityResolver = {
            if (available) NativePushCapability.Available else NativePushCapability.FirebaseUnavailable
        },
        fcmTokenReader = reader,
    )
}
