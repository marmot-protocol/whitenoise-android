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
            val fixture = fixture(available = false) {
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
