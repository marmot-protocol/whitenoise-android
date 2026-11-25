package dev.ipf.whitenoise.android.state

import android.Manifest
import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.PushRegistrationShareOutcomeFfi
import dev.ipf.marmotkit.PushRegistrationShareStatusFfi
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenKeyRule
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Delivery changes that do not complete still settle Firebase auto-init from the accounts' live demand (#4081):
 * a Local selection cancelled after the last account's native disable committed, and a per-account native-push
 * enablement that fails and is rolled back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FcmAutoInitIncompleteChangeTest {
    @get:Rule
    val pushTokenKey = PushTokenKeyRule()

    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    /** Starts with the persistent connection off and the notification permission granted. */
    @Before
    fun resetDeliveryState() {
        BackgroundConnectionPreferences.setEnabledDurably(context, false)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Stops the foreground service the Local selection started. */
    @After
    fun stopTestService() {
        NotificationStreamForegroundService.stop(context)
    }

    /** Cancelling Local while the last Push account's registration clear runs still turns auto-init off. */
    @Test
    fun cancelledLocalCutoverAfterLastNativeDisableTurnsAutoInitOff() =
        runBlocking {
            val cleanupStarted = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A)),
                    initialNotificationSettings = settings(ACCOUNT_A, nativeEnabled = false),
                    nativePushFallbackPlatform = platform,
                    onClearPushRegistration = {
                        cleanupStarted.countDown()
                        check(releaseCleanup.await(10, TimeUnit.SECONDS))
                        completedClear()
                    },
                    pushServerConfigProvider = { CONFIG },
                    fcmTokenReader = { "test-token" },
                    nativePushCapabilityResolver = { NativePushCapability.Available },
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                fixture.runWithMainLooperPumping { withTimeout(10_000L) { platform.awaitFirstStart() } }
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("Local service acknowledgement was not accepted")
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) { while (cleanupStarted.count > 0L) yield() }
                }
                // The native disable has committed; the cutover's own off write has not run yet.
                platform.autoInitWrites.clear()

                result.cancel()
                releaseCleanup.countDown()
                fixture.runWithMainLooperPumping { result.join().also { acknowledgement.join() } }

                assertTrue(result.isCancelled)
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertEquals(false, platform.autoInitWrites.lastOrNull())
                assertFalse(true in platform.autoInitWrites)
            } finally {
                releaseCleanup.countDown()
                fixture.close()
            }
        }

    /** A per-account Push enablement whose token read fails is rolled back and leaves auto-init off. */
    @Test
    fun failedPerAccountEnablementLeavesAutoInitOff() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A)),
                    initialNotificationSettings = settings(ACCOUNT_A, nativeEnabled = false),
                    nativePushFallbackPlatform = platform,
                    pushServerConfigProvider = { CONFIG },
                    fcmTokenReader = { null },
                    nativePushCapabilityResolver = { NativePushCapability.Available },
                )
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()

                assertFalse(fixture.runWithMainLooperPumping { fixture.appState.setNativePushEnabled(true) })

                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertEquals(false, platform.autoInitWrites.lastOrNull())
            } finally {
                fixture.close()
            }
        }

    /** Builds a running local-signing account summary for the given ref. */
    private fun account(ref: String) = AccountSummaryFfi(ref, "$ref-id", true, false, false, true, false)

    /** Builds notification settings with rendering on and the given native-push flag. */
    private fun settings(
        ref: String,
        nativeEnabled: Boolean,
    ) = NotificationSettingsFfi(ref, "$ref-id", true, nativeEnabled)

    /** A completed, zero-group registration clear. */
    private fun completedClear() =
        PushRegistrationShareOutcomeFfi(
            status = PushRegistrationShareStatusFfi.COMPLETE,
            attemptedGroups = 0u,
            succeededGroups = 0u,
            failedGroups = 0u,
            pendingGroups = 0u,
        )

    private companion object {
        const val ACCOUNT_A = "account-a"
        val CONFIG = PushServerConfig("0".repeat(64), null)
    }
}
