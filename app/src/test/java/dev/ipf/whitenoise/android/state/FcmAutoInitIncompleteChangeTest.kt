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
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Delivery changes that do not complete still settle Firebase auto-init from the accounts' live demand (#4081):
 * a Local selection cancelled after the last account's native disable committed, a per-account native-push
 * enablement that fails and is rolled back, a per-account disable cancelled after its native commit, and a
 * reconciliation that leaves no account on Push. A cancelled per-account enable, including one cancelled
 * during its rollback, leaves a registration sync pending instead.
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
                // The native disable has committed, and the cutover's own off write has not run yet.
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

    /** A per-account Push disable cancelled right after its native `false` commits still turns auto-init off. */
    @Test
    fun cancelledPerAccountDisableAfterNativeCommitTurnsAutoInitOff() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val toggle = AtomicReference<Job?>()
            val cancelOnDisable = AtomicBoolean(false)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A)),
                    initialNotificationSettings = settings(ACCOUNT_A, nativeEnabled = true),
                    nativePushFallbackPlatform = platform,
                    onSetNativePushEnabled = { ref, enabled ->
                        // The native disable commits, then the toggle is cancelled before it can settle inline.
                        if (!enabled && cancelOnDisable.get()) toggle.get()?.cancel()
                        settings(ref, nativeEnabled = enabled)
                    },
                    pushServerConfigProvider = { CONFIG },
                    fcmTokenReader = { "test-token" },
                    nativePushCapabilityResolver = { NativePushCapability.Available },
                )
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()
                cancelOnDisable.set(true)

                val job = launch { fixture.appState.setNativePushEnabled(false) }
                toggle.set(job)
                fixture.runWithMainLooperPumping { job.join() }

                assertTrue("the toggle must have been cancelled", job.isCancelled)
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertEquals(false, platform.autoInitWrites.lastOrNull())
            } finally {
                cancelOnDisable.set(false)
                fixture.close()
            }
        }

    /** A per-account Push enable cancelled after its native commit leaves a durable registration sync pending. */
    @Test
    fun cancelledPerAccountEnableAfterNativeCommitRecordsPendingSync() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val toggle = AtomicReference<Job?>()
            val cancelOnEnable = AtomicBoolean(false)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A)),
                    initialNotificationSettings = settings(ACCOUNT_A, nativeEnabled = false),
                    nativePushFallbackPlatform = platform,
                    onSetNativePushEnabled = { ref, enabled ->
                        // The native enable commits, then the toggle is cancelled before it registers.
                        if (enabled && cancelOnEnable.get()) toggle.get()?.cancel()
                        settings(ref, nativeEnabled = enabled)
                    },
                    pushServerConfigProvider = { CONFIG },
                    fcmTokenReader = { "test-token" },
                    nativePushCapabilityResolver = { NativePushCapability.Available },
                )
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                cancelOnEnable.set(true)

                val job = launch { fixture.appState.setNativePushEnabled(true) }
                toggle.set(job)
                fixture.runWithMainLooperPumping { job.join() }

                assertTrue("the toggle must have been cancelled", job.isCancelled)
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue("the next sync must register the account", store.nativePushRegistrationSyncPending())
                cancelOnEnable.set(false)
                platform.autoInitWrites.clear()

                assertTrue(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })

                assertTrue(ACCOUNT_A in fixture.upsertedPushRegistrations)
                assertEquals(true, platform.autoInitWrites.lastOrNull())
                assertFalse("the sync must acknowledge the marker", store.nativePushRegistrationSyncPending())
            } finally {
                cancelOnEnable.set(false)
                fixture.close()
            }
        }

    /** A failed per-account enable whose rollback is cancelled still leaves a durable registration sync pending. */
    @Test
    fun cancelledRollbackOfFailedEnableRecordsPendingSync() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val toggle = AtomicReference<Job?>()
            val cancelOnRollback = AtomicBoolean(false)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A)),
                    initialNotificationSettings = settings(ACCOUNT_A, nativeEnabled = false),
                    nativePushFallbackPlatform = platform,
                    onSetNativePushEnabled = { ref, enabled ->
                        // The token read fails, and the cancellation lands on the rollback's native write.
                        if (!enabled && cancelOnRollback.get()) toggle.get()?.cancel()
                        settings(ref, nativeEnabled = enabled)
                    },
                    pushServerConfigProvider = { CONFIG },
                    fcmTokenReader = { null },
                    nativePushCapabilityResolver = { NativePushCapability.Available },
                )
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                cancelOnRollback.set(true)

                val job = launch { fixture.appState.setNativePushEnabled(true) }
                toggle.set(job)
                fixture.runWithMainLooperPumping { job.join() }

                assertTrue("the rollback must have been cancelled", job.isCancelled)
                assertTrue("the next sync must reconcile the account", store.nativePushRegistrationSyncPending())
            } finally {
                cancelOnRollback.set(false)
                fixture.close()
            }
        }

    /**
     * A reconciliation that leaves every account off native push, because rendering is off for all of them,
     * registers nothing and turns auto-init off, even when an earlier sync had turned it on.
     */
    @Test
    fun reconciliationLeavingNoPushAccountTurnsAutoInitOff() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(ACCOUNT_A)),
                    initialNotificationSettings = settings(ACCOUNT_A, nativeEnabled = true, localEnabled = false),
                    nativePushFallbackPlatform = platform,
                    pushServerConfigProvider = { CONFIG },
                    fcmTokenReader = { "test-token" },
                    nativePushCapabilityResolver = { NativePushCapability.Available },
                )
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()
                // As an earlier sync for the account's native flag would have left it.
                platform.setFcmAutoInitEnabled(true)
                val delivery = fixture.appState.notificationDelivery
                val intent = delivery.notificationDeliveryModeIntent.capture()
                val owner = checkNotNull(delivery.captureNotificationDeliveryModeOwner(intent))

                fixture.runWithMainLooperPumping { delivery.selectFcmDelivery(owner) }

                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                val writes = platform.autoInitWrites.drop(1)
                assertFalse("no account uses Push, so auto-init must not turn on: $writes", true in writes)
                assertEquals("the reconciliation must turn it off", false, writes.lastOrNull())
            } finally {
                fixture.close()
            }
        }

    /** Builds a running local-signing account summary for the given ref. */
    private fun account(ref: String) = AccountSummaryFfi(ref, "$ref-id", true, false, false, true, false)

    /** Builds notification settings with the given rendering and native-push flags. */
    private fun settings(
        ref: String,
        nativeEnabled: Boolean,
        localEnabled: Boolean = true,
    ) = NotificationSettingsFfi(ref, "$ref-id", localEnabled, nativeEnabled)

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
