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
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("LargeClass") // One fixture-backed suite covers the complete two-transport settlement state machine.
class NotificationDeliveryModeRecoveryTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun resetDeliveryState() {
        BackgroundConnectionPreferences.setEnabledDurably(context, false)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun stopTestService() {
        NotificationStreamForegroundService.stop(context)
    }

    /** A rejected service request restores both durable and projected persistent state. */
    @Test
    fun rejectedLocalSelectionRestoresPreviousPreference() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context, startResults = ArrayDeque(listOf(false)))
            val fixture = fixture(platform = platform, nativeEnabled = false)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))

                assertFalse(fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local))

                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(fixture.appState.backgroundConnectionEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertEquals(1, platform.stops.get())
            } finally {
                fixture.close()
            }
        }

    /** Cancelling an unacknowledged Local request restores the previous durable choice. */
    @Test
    fun cancelledLocalSelectionRestoresPreviousPreference() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform = platform, nativeEnabled = false)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                awaitStart(fixture, platform)

                result.cancelAndJoin()

                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(fixture.appState.backgroundConnectionEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertEquals(1, platform.stops.get())
            } finally {
                fixture.close()
            }
        }

    /** Cancellation delivered after native disable keeps the acknowledged Local transport durable. */
    @Test
    fun cancelledLocalCutoverCannotDisableBothTransports() =
        runBlocking {
            val disabling = CountDownLatch(1)
            val releaseDisable = CountDownLatch(1)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    onSetNative = { accountRef, enabled ->
                        if (!enabled) {
                            disabling.countDown()
                            check(releaseDisable.await(10, TimeUnit.SECONDS))
                        }
                        settings(accountRef, nativeEnabled = enabled)
                    },
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                awaitStart(fixture, platform)
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("Local service acknowledgement was not accepted")
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) { while (disabling.count > 0L) yield() }
                }

                result.cancel()
                releaseDisable.countDown()
                fixture.runWithMainLooperPumping { result.join().also { acknowledgement.join() } }

                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertEquals(0, platform.stops.get())
            } finally {
                releaseDisable.countDown()
                fixture.close()
            }
        }

    /** Cancellation during registration cleanup cannot stop Local after native disable committed. */
    @Test
    fun cancelledLocalCleanupKeepsAcknowledgedTransport() =
        runBlocking {
            val cleanupStarted = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    onClear = {
                        cleanupStarted.countDown()
                        check(releaseCleanup.await(10, TimeUnit.SECONDS))
                        completedPushRegistrationClear()
                    },
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                awaitStart(fixture, platform)
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("Local service acknowledgement was not accepted")
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) { while (cleanupStarted.count > 0L) yield() }
                }

                result.cancel()
                releaseCleanup.countDown()
                fixture.runWithMainLooperPumping { result.join().also { acknowledgement.join() } }

                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertEquals(0, platform.stops.get())
            } finally {
                releaseCleanup.countDown()
                fixture.close()
            }
        }

    /** A second-account setup failure retains Local after the first account's native disable. */
    @Test
    fun partialMultiAccountFailureKeepsAcknowledgedLocalTransport() =
        runBlocking {
            val clearCalls = AtomicInteger()
            val platform =
                RecordingNativePushFallbackPlatform(
                    context = context,
                    beforeClear = {
                        if (clearCalls.incrementAndGet() == 2) throw IOException("second cleanup unavailable")
                    },
                )
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                awaitStart(fixture, platform)
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("Local service acknowledgement was not accepted")

                assertFalse(fixture.runWithMainLooperPumping { result.await().also { acknowledgement.join() } })
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertEquals(0, platform.stops.get())
            } finally {
                fixture.close()
            }
        }

    /** Cold foreground migration establishes a real owner when rendering is on but no transport is configured. */
    @Test
    fun coldForegroundRepairsLegacyRenderingOnWithoutTransport() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform = platform, nativeEnabled = false)
            try {
                fixture.appState.setAppInForeground(true)
                fixture.bootstrap()
                awaitStart(fixture, platform)
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.last())

                fixture.runWithMainLooperPumping {
                    withTimeout(2_000L) {
                        while (!fixture.appState.backgroundConnectionEnabled) yield()
                    }
                }
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
            } finally {
                fixture.appState.setAppInForeground(false)
                fixture.close()
            }
        }

    /** Cold foreground migration establishes Local without undoing an explicit rendering opt-out. */
    @Test
    fun coldForegroundPreservesLegacyAllOffRendering() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                )
            try {
                fixture.replaceNotificationSettings(
                    settings(ACCOUNT_A, localEnabled = false, nativeEnabled = false),
                )
                fixture.replaceNotificationSettings(
                    settings(ACCOUNT_B, localEnabled = false, nativeEnabled = false),
                )
                fixture.appState.setAppInForeground(true)

                fixture.bootstrap()
                awaitStart(fixture, platform)
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.last())
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(fixture.notificationSettings(ACCOUNT_A).localNotificationsEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_B).localNotificationsEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
            } finally {
                fixture.appState.setAppInForeground(false)
                fixture.close()
            }
        }

    /** A cold foreground edge before bootstrap preserves disabled rendering during activation. */
    @Test
    fun foregroundBeforeBootstrapPreservesRenderingOptOut() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform = platform, nativeEnabled = true)
            try {
                fixture.replaceNotificationSettings(
                    settings(ACCOUNT_A, localEnabled = false, nativeEnabled = true),
                )
                fixture.appState.setAppInForeground(true)

                fixture.bootstrap()

                assertFalse(fixture.notificationSettings(ACCOUNT_A).localNotificationsEnabled)
                awaitStart(fixture, platform)
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.last())
                fixture.runWithMainLooperPumping {
                    withTimeout(2_000L) {
                        while (!fixture.appState.backgroundConnectionEnabled) yield()
                    }
                }
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
            } finally {
                fixture.appState.setAppInForeground(false)
                fixture.close()
            }
        }

    /** Foreground capability fallback keeps a disabled rendering preference unchanged. */
    @Test
    fun foregroundReconciliationPreservesRenderingOptOut() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform = platform, nativeEnabled = true)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, localEnabled = false, nativeEnabled = true))
                fixture.appState.refreshLocalNotificationSettings()
                fixture.appState.setAppInForeground(true)
                awaitStart(fixture, platform)
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.last())

                assertFalse(fixture.notificationSettings(ACCOUNT_A).localNotificationsEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
            } finally {
                fixture.appState.setAppInForeground(false)
                fixture.close()
            }
        }

    /** Same-account activation does not turn local notifications back on. */
    @Test
    fun sameAccountActivationPreservesRenderingOptOut() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform = platform, nativeEnabled = false)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(
                    settings(ACCOUNT_A, localEnabled = false, nativeEnabled = false),
                )

                assertTrue(fixture.runOnMainLooperPumping { fixture.appState.setActiveAccount(ACCOUNT_A) })

                assertFalse(fixture.notificationSettings(ACCOUNT_A).localNotificationsEnabled)
            } finally {
                fixture.close()
            }
        }

    /** Choosing a delivery mode explicitly opts the account back into visible notifications. */
    @Test
    fun explicitModeSelectionEnablesRenderingAfterOptOut() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    initialLocalEnabled = false,
                    fcmAvailable = true,
                )
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")

                assertTrue(fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm))
                assertTrue(fixture.notificationSettings(ACCOUNT_A).localNotificationsEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertEquals(listOf(ACCOUNT_A), fixture.upsertedPushRegistrations)
            } finally {
                fixture.close()
            }
        }

    /** Foreground permission loss drains required cleanup without registration or capability fallback. */
    @Test
    fun foregroundPermissionLossPausesTransportAndRetainsMode() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform = platform, nativeEnabled = false)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                fixture.appState.refreshLocalNotificationSettings()
                PushTokenStore.create(context).recordPendingClear(ACCOUNT_A)
                shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

                fixture.appState.setAppInForeground(true)
                fixture.runWithMainLooperPumping {
                    withTimeout(2_000L) {
                        while (fixture.clearedPushRegistrations.isEmpty()) yield()
                    }
                }

                assertFalse(fixture.appState.localNotificationPermissionGranted)
                assertTrue(platform.starts.isEmpty())
                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertEquals(listOf(ACCOUNT_A), fixture.clearedPushRegistrations)
            } finally {
                fixture.appState.setAppInForeground(false)
                fixture.close()
            }
        }

    /** Permission restoration does not override an account's disabled rendering preference. */
    @Test
    fun activationPermissionRegrantPreservesRenderingOptOut() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform = platform, nativeEnabled = false)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(
                    settings(ACCOUNT_A, localEnabled = false, nativeEnabled = true),
                )
                shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

                assertTrue(fixture.runOnMainLooperPumping { fixture.appState.setActiveAccount(ACCOUNT_A) })
                assertFalse(fixture.notificationSettings(ACCOUNT_A).localNotificationsEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(platform.starts.isEmpty())
                assertFalse(BackgroundConnectionPreferences.isEnabled(context))

                shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
                assertTrue(fixture.runOnMainLooperPumping { fixture.appState.setActiveAccount(ACCOUNT_A) })
                assertFalse(fixture.notificationSettings(ACCOUNT_A).localNotificationsEnabled)
                awaitStart(fixture, platform)
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.last())
                fixture.runWithMainLooperPumping {
                    withTimeout(2_000L) {
                        while (fixture.notificationSettings(ACCOUNT_A).nativePushEnabled) yield()
                    }
                }

                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
            } finally {
                fixture.close()
            }
        }

    /** Device Local mode disables native delivery for the active and background accounts. */
    @Test
    fun localSelectionReconcilesEverySignedInAccount() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                )
            var transition: Job? = null
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                transition = result
                awaitStart(fixture, platform)
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("Local service acknowledgement was not accepted")

                assertTrue(
                    fixture.runWithMainLooperPumping {
                        result.await().also { acknowledgement.join() }
                    },
                )
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
                assertEquals(setOf(ACCOUNT_A, ACCOUNT_B), fixture.clearedPushRegistrations.toSet())
            } finally {
                transition?.cancelAndJoin()
                fixture.close()
            }
        }

    /** A per-account disable failure keeps persistent delivery running and reports incomplete. */
    @Test
    fun partialLocalCutoverKeepsPersistentDeliveryEstablished() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                    onSetNative = { accountRef, enabled ->
                        settings(accountRef, nativeEnabled = if (accountRef == ACCOUNT_B && !enabled) true else enabled)
                    },
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                awaitStart(fixture, platform)
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("Local service acknowledgement was not accepted")

                assertFalse(
                    fixture.runWithMainLooperPumping {
                        result.await().also { acknowledgement.join() }
                    },
                )
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
            } finally {
                fixture.close()
            }
        }

    /** An absent foreground service already satisfies the stopped-state boundary. */
    @Test
    fun stoppingAnAbsentServiceSucceeds() {
        assertTrue(NotificationStreamForegroundService.stop(context))
        assertTrue(NotificationStreamForegroundService.stop(context))
    }

    /** Cold native selection registers every relevant account before the global service cutover. */
    @Test
    fun coldFcmSelectionRegistersAllAccountsAndStopsPersistentDelivery() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fcmFixture(platform)
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")

                assertTrue(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                    },
                )

                assertEquals(setOf(ACCOUNT_A, ACCOUNT_B), fixture.upsertedPushRegistrations.toSet())
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
                assertEquals(1, platform.stops.get())
            } finally {
                fixture.close()
            }
        }

    /** Slow per-account registration overlaps only within the fixed batch and never stops Local early. */
    @Test
    fun fcmRegistrationIsBoundedAndKeepsLocalUntilEveryAccountIsReady() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val threeStarted = CountDownLatch(3)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val started = AtomicInteger()
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B), account("account-c"), account("account-d")),
                    onUpsert = {
                        started.incrementAndGet()
                        maximum.accumulateAndGet(active.incrementAndGet()) { current, candidate ->
                            maxOf(current, candidate)
                        }
                        threeStarted.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        active.decrementAndGet()
                    },
                    fcmAvailable = true,
                )
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                withTimeout(5_000L) { while (threeStarted.count > 0L) yield() }

                assertEquals(3, started.get())
                assertEquals(3, maximum.get())
                assertEquals(0, platform.stops.get())
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))

                release.countDown()
                assertTrue(fixture.runWithMainLooperPumping { result.await() })
                assertEquals(4, started.get())
                assertEquals(3, maximum.get())
                assertEquals(1, platform.stops.get())
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** A failed concurrent registration restores changed accounts without dropping Local delivery. */
    @Test
    fun concurrentFcmRegistrationFailureKeepsLocalDelivery() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fcmFixture(platform) { account ->
                if (account == ACCOUNT_B) error("registration failed")
            }
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")

                assertFalse(fixture.runWithMainLooperPumping { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) })
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertEquals(0, platform.stops.get())
            } finally {
                fixture.close()
            }
        }

    /** Reselecting an already-native mode still treats an absent service as a settled success. */
    @Test
    fun fcmReselectionSucceedsWhenPersistentServiceIsAlreadyAbsent() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fcmFixture(platform)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                PushTokenStore.create(context).setToken("test-token")

                assertTrue(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                    },
                )

                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
                assertEquals(1, platform.stops.get())
            } finally {
                fixture.close()
            }
        }

    /** Cancellation during registration restores native settings and retains persistent delivery. */
    @Test
    fun cancelledFcmSelectionRetainsThePreviousTransport() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val upsertStarted = CountDownLatch(1)
            val releaseUpsert = CountDownLatch(1)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fcmFixture(platform) {
                    upsertStarted.countDown()
                    releaseUpsert.await()
                }
            var transition: Job? = null
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                transition = result
                withTimeout(2_000L) { while (upsertStarted.count > 0L) yield() }

                result.cancel()
                releaseUpsert.countDown()
                result.cancelAndJoin()

                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertEquals(0, platform.stops.get())
            } finally {
                releaseUpsert.countDown()
                transition?.cancelAndJoin()
                fixture.close()
            }
        }

    /** Cancellation after the false preference commit restores durable persistent ownership before native rollback. */
    @Test
    fun cancelledFcmCutoverRestoresDurablePersistentPreference() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val committed = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            val recording = RecordingNativePushFallbackPlatform(context)
            val platform =
                object : NativePushFallbackPlatform by recording {
                    override fun persistBackgroundConnectionEnabled(
                        enabled: Boolean,
                        isStillDesired: () -> Boolean,
                    ): Boolean {
                        val persisted = recording.persistBackgroundConnectionEnabled(enabled, isStillDesired)
                        if (!enabled) {
                            committed.countDown()
                            check(releaseCommit.await(10, TimeUnit.SECONDS))
                        }
                        return persisted
                    }
                }
            val fixture = fixture(platform = platform, nativeEnabled = false, fcmAvailable = true)
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                withTimeout(5_000L) { while (committed.count > 0L) yield() }

                result.cancel()
                releaseCommit.countDown()
                fixture.runWithMainLooperPumping { result.join() }

                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertEquals(0, recording.stops.get())
            } finally {
                releaseCommit.countDown()
                fixture.close()
            }
        }

    /** A stop-boundary failure re-establishes and acknowledges Local before native delivery is rolled back. */
    @Test
    fun failedFcmStopRestoresPersistentRuntimeBeforeNativeRollback() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val stopAttempted = CountDownLatch(1)
            val recording = RecordingNativePushFallbackPlatform(context)
            val platform =
                object : NativePushFallbackPlatform by recording {
                    override fun stopBackgroundConnection(): Boolean {
                        recording.stopBackgroundConnection()
                        stopAttempted.countDown()
                        throw IOException("stop result unavailable")
                    }
                }
            val fixture = fixture(platform = platform, nativeEnabled = false, fcmAvailable = true)
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                withTimeout(5_000L) { while (stopAttempted.count > 0L) yield() }
                awaitStart(fixture, recording)
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(recording.starts.last())
                        ?: error("restored Local service acknowledgement was not accepted")

                assertFalse(fixture.runWithMainLooperPumping { result.await().also { acknowledgement.join() } })
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertEquals(1, recording.stops.get())
            } finally {
                fixture.close()
            }
        }

    /** Cancellation after service stop begins re-establishes Local before native delivery is rolled back. */
    @Test
    fun cancelledFcmStopRestoresPersistentRuntimeBeforeNativeRollback() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val stopStarted = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            val recording = RecordingNativePushFallbackPlatform(context)
            val platform =
                object : NativePushFallbackPlatform by recording {
                    override fun stopBackgroundConnection(): Boolean {
                        recording.stopBackgroundConnection()
                        stopStarted.countDown()
                        check(releaseStop.await(10, TimeUnit.SECONDS))
                        return true
                    }
                }
            val fixture = fixture(platform = platform, nativeEnabled = false, fcmAvailable = true)
            var transition: Job? = null
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                transition = result
                withTimeout(5_000L) { while (stopStarted.count > 0L) yield() }

                result.cancel()
                releaseStop.countDown()
                awaitStart(fixture, recording)
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(recording.starts.last())
                        ?: error("restored Local service acknowledgement was not accepted")
                fixture.runWithMainLooperPumping { result.join().also { acknowledgement.join() } }

                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertEquals(1, recording.stops.get())
            } finally {
                releaseStop.countDown()
                transition?.cancelAndJoin()
                fixture.close()
            }
        }

    /** Account activation preserves disabled rendering while settling Local asynchronously. */
    @Test
    fun accountActivationPreservesRenderingAndSettlesLocalMode() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, localEnabled = false, nativeEnabled = false))

                assertTrue(fixture.runOnMainLooperPumping { fixture.appState.setActiveAccount(ACCOUNT_B) })
                assertFalse(fixture.notificationSettings(ACCOUNT_B).localNotificationsEnabled)
                awaitStart(fixture, platform)
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.last())
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) {
                        while (!fixture.appState.backgroundConnectionEnabled) yield()
                    }
                }

                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
            } finally {
                fixture.close()
            }
        }

    /** A real account switch reconciles device-wide Local even when active rendering already matches. */
    @Test
    fun accountActivationReconcilesNativeFlagWhenRenderingAlreadyEnabled() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(
                    settings(ACCOUNT_B, localEnabled = true, nativeEnabled = true),
                )

                assertTrue(fixture.runOnMainLooperPumping { fixture.appState.setActiveAccount(ACCOUNT_B) })
                awaitStart(fixture, platform)
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.last())
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) {
                        while (fixture.notificationSettings(ACCOUNT_B).nativePushEnabled) yield()
                    }
                }

                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.notificationSettings(ACCOUNT_B).localNotificationsEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
            } finally {
                fixture.close()
            }
        }

    /** A superseding account-switch generation rejects an activation-owned Local reconciliation. */
    @Test
    fun accountSwitchSupersedesActivationModeReconciliation() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, localEnabled = false, nativeEnabled = true))
                assertTrue(fixture.runOnMainLooperPumping { fixture.appState.setActiveAccount(ACCOUNT_B) })
                awaitStart(fixture, platform)

                assertFalse(
                    fixture.runOnMainLooperPumping {
                        fixture.appState.setActiveAccount(ACCOUNT_A, shouldActivate = { false })
                    },
                )
                fixture.rejectNativePushFallbackRuntime(platform.starts.last())
                fixture.runWithMainLooperPumping { yield() }

                assertFalse(fixture.notificationSettings(ACCOUNT_B).localNotificationsEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
            } finally {
                fixture.close()
            }
        }

    /** Activation under a settled native mode enables rendering and registers the newly active account. */
    @Test
    fun accountActivationAppliesSettledFcmMode() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                    fcmAvailable = true,
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, localEnabled = true, nativeEnabled = false))
                PushTokenStore.create(context).setToken("test-token")

                assertTrue(fixture.runOnMainLooperPumping { fixture.appState.setActiveAccount(ACCOUNT_B) })
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) {
                        while (
                            !fixture.notificationSettings(ACCOUNT_B).nativePushEnabled ||
                            !fixture.upsertedPushRegistrations.containsAll(listOf(ACCOUNT_A, ACCOUNT_B))
                        ) {
                            yield()
                        }
                    }
                }

                assertTrue(fixture.notificationSettings(ACCOUNT_B).localNotificationsEnabled)
                assertEquals(setOf(ACCOUNT_A, ACCOUNT_B), fixture.upsertedPushRegistrations.toSet())
                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
            } finally {
                fixture.close()
            }
        }

    /** Activation keeps the opted-out account disabled while registering another enabled account. */
    @Test
    fun activationPreservesOptOutUnderSettledFcmMode() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    initialLocalEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                    fcmAvailable = true,
                )
            try {
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")
                assertTrue(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })
                assertTrue(ACCOUNT_B in fixture.upsertedPushRegistrations)
                assertFalse(ACCOUNT_A in fixture.upsertedPushRegistrations)

                assertTrue(fixture.runOnMainLooperPumping { fixture.appState.setActiveAccount(ACCOUNT_A) })
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) {
                        while (fixture.appState.notificationDeliveryModeBusy) yield()
                    }
                }

                assertFalse(fixture.notificationSettings(ACCOUNT_A).localNotificationsEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertFalse(ACCOUNT_A in fixture.upsertedPushRegistrations)
                assertTrue(ACCOUNT_B in fixture.upsertedPushRegistrations)
                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
            } finally {
                fixture.close()
            }
        }

    /** A failed activation plan releases the busy flag after superseding a held selection. */
    @Test
    fun activationWithoutCutoverReleasesSupersededBusySelector() =
        runBlocking {
            val nativeWriteStarted = CountDownLatch(1)
            val releaseNativeWrite = CountDownLatch(1)
            val failBackgroundSettingsRead = AtomicBoolean(false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                    fcmAvailable = true,
                    onSetNative = { accountRef, enabled ->
                        if (enabled) {
                            nativeWriteStarted.countDown()
                            check(releaseNativeWrite.await(10, TimeUnit.SECONDS))
                        }
                        settings(accountRef, nativeEnabled = false)
                    },
                    onNotificationSettings = { accountRef ->
                        if (accountRef == ACCOUNT_B && failBackgroundSettingsRead.get()) {
                            throw IOException("background account settings unavailable")
                        }
                        settings(accountRef, nativeEnabled = false)
                    },
                )
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")

                val selection = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                withTimeout(2_000L) { while (nativeWriteStarted.count > 0L) yield() }
                failBackgroundSettingsRead.set(true)
                val selectionGeneration = notificationDeliveryModeGeneration(fixture.appState)
                val activation =
                    async { fixture.runOnMainLooperPumping { fixture.appState.setActiveAccount(ACCOUNT_A) } }
                withTimeout(5_000L) {
                    while (notificationDeliveryModeGeneration(fixture.appState) == selectionGeneration) yield()
                }
                releaseNativeWrite.countDown()

                assertFalse(fixture.runWithMainLooperPumping { selection.await() })
                assertTrue(fixture.runWithMainLooperPumping { activation.await() })
                assertFalse(fixture.appState.notificationDeliveryModeBusy)
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
            } finally {
                releaseNativeWrite.countDown()
                fixture.close()
            }
        }

    /** An A-B-A switch generation invalidates held registration before global cutover. */
    @Test
    fun accountSwitchGenerationFencesHeldFcmSelection() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val upsertStarted = CountDownLatch(1)
            val releaseUpsert = CountDownLatch(1)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fcmFixture(platform) {
                    upsertStarted.countDown()
                    releaseUpsert.await()
                }
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                withTimeout(2_000L) { while (upsertStarted.count > 0L) yield() }

                assertFalse(
                    fixture.runOnMainLooperPumping {
                        fixture.appState.setActiveAccount(ACCOUNT_B, shouldActivate = { false })
                    },
                )
                assertFalse(
                    fixture.runOnMainLooperPumping {
                        fixture.appState.setActiveAccount(ACCOUNT_A, shouldActivate = { false })
                    },
                )
                releaseUpsert.countDown()

                assertFalse(result.await())
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertEquals(0, platform.stops.get())
            } finally {
                releaseUpsert.countDown()
                fixture.close()
            }
        }

    /** A rejected cutover drains token rotation recorded while the delivery mutex was busy. */
    @Test
    fun tokenRotationDuringRejectedLocalCutoverIsDrained() =
        runBlocking {
            val persistStarted = CountDownLatch(1)
            val releasePersist = CountDownLatch(1)
            val platform =
                RecordingNativePushFallbackPlatform(
                    context = context,
                    startResults = ArrayDeque(listOf(false)),
                    beforePersist = {
                        persistStarted.countDown()
                        check(releasePersist.await(10, TimeUnit.SECONDS))
                    },
                )
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = true,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                    fcmAvailable = true,
                )
            val store = PushTokenStore.create(context)
            try {
                fixture.bootstrap()
                fixture.runWithMainLooperPumping {
                    withTimeout(2_000L) { while (fixture.appState.notificationDeliveryModeBusy) yield() }
                }
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                store.setToken("old-token")
                assertTrue(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })
                fixture.upsertedPushRegistrations.clear()
                fixture.upsertedPushTokens.clear()

                val local = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                withTimeout(2_000L) { while (persistStarted.count > 0L) yield() }
                fixture.runWithMainLooperPumping {
                    fixture.appState.onPushTokenRotated("rotated-token")
                    withTimeout(2_000L) { while (!store.nativePushRegistrationSyncPending()) yield() }
                }
                releasePersist.countDown()

                assertFalse(fixture.runWithMainLooperPumping { local.await() })
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) { while (store.nativePushRegistrationSyncPending()) yield() }
                }
                assertEquals(setOf(ACCOUNT_A, ACCOUNT_B), fixture.upsertedPushRegistrations.toSet())
                assertEquals(setOf("rotated-token"), fixture.upsertedPushTokens.toSet())
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
            } finally {
                releasePersist.countDown()
                store.clearPendingNativePushRegistrationSync()
                store.setToken("")
                fixture.close()
            }
        }

    /** Runtime replacement rejects held registration and retains the prior global transport. */
    @Test
    fun runtimeReplacementFencesHeldFcmSelection() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val upsertStarted = CountDownLatch(1)
            val releaseUpsert = CountDownLatch(1)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fcmFixture(platform) {
                    upsertStarted.countDown()
                    releaseUpsert.await()
                }
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                withTimeout(2_000L) { while (upsertStarted.count > 0L) yield() }

                replaceRuntimeOwner(fixture.appState)
                releaseUpsert.countDown()

                assertFalse(result.await())
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertEquals(0, platform.stops.get())
            } finally {
                releaseUpsert.countDown()
                fixture.close()
            }
        }

    /** A newer Local intent owns convergence after superseding held native registration. */
    @Test
    fun newerLocalIntentSupersedesHeldFcmSelection() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val upsertStarted = CountDownLatch(1)
            val releaseUpsert = CountDownLatch(1)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fcmFixture(platform) {
                    upsertStarted.countDown()
                    releaseUpsert.await()
                }
            var local: Job? = null
            try {
                fixture.bootstrap()
                PushTokenStore.create(context).setToken("test-token")
                val fcm = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                withTimeout(2_000L) { while (upsertStarted.count > 0L) yield() }
                val localResult = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                local = localResult
                releaseUpsert.countDown()

                assertFalse(fcm.await())
                awaitStart(fixture, platform)
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("newer Local acknowledgement was not accepted")
                assertTrue(
                    fixture.runWithMainLooperPumping {
                        localResult.await().also { acknowledgement.join() }
                    },
                )
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
            } finally {
                releaseUpsert.countDown()
                local?.cancelAndJoin()
                fixture.close()
            }
        }

    /** A newer FCM intent owns convergence after superseding a partially committed Local cutover. */
    @Test
    fun newerFcmIntentSettlesAfterPartialLocalCutover() =
        runBlocking {
            val secondDisableStarted = CountDownLatch(1)
            val releaseSecondDisable = CountDownLatch(1)
            val disableCalls = AtomicInteger()
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
                    fcmAvailable = true,
                    onSetNative = { accountRef, enabled ->
                        if (!enabled && disableCalls.incrementAndGet() == 2) {
                            secondDisableStarted.countDown()
                            check(releaseSecondDisable.await(10, TimeUnit.SECONDS))
                        }
                        settings(accountRef, nativeEnabled = enabled)
                    },
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_A, nativeEnabled = true))
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                PushTokenStore.create(context).setToken("test-token")
                val local = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                awaitStart(fixture, platform)
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("Local service acknowledgement was not accepted")
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) { while (secondDisableStarted.count > 0L) yield() }
                }
                val fcm = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }

                releaseSecondDisable.countDown()
                assertFalse(fixture.runWithMainLooperPumping { local.await().also { acknowledgement.join() } })
                assertTrue(fixture.runWithMainLooperPumping { fcm.await() })

                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
                assertEquals(1, platform.stops.get())
            } finally {
                releaseSecondDisable.countDown()
                fixture.close()
            }
        }

    private fun fcmFixture(
        platform: RecordingNativePushFallbackPlatform,
        onUpsert: (String) -> Unit = {},
    ) = fixture(
        platform = platform,
        nativeEnabled = false,
        accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
        onUpsert = onUpsert,
        fcmAvailable = true,
    )

    private fun fixture(
        platform: NativePushFallbackPlatform,
        nativeEnabled: Boolean,
        initialLocalEnabled: Boolean = true,
        accounts: List<AccountSummaryFfi> = listOf(account(ACCOUNT_A)),
        onUpsert: (String) -> Unit = {},
        fcmAvailable: Boolean = false,
        onSetNative: ((String, Boolean) -> NotificationSettingsFfi)? = null,
        onClear: ((String) -> PushRegistrationShareOutcomeFfi)? = null,
        onNotificationSettings: ((String) -> NotificationSettingsFfi)? = null,
    ) = NotificationBootstrapTestFixture(
        context = context,
        accounts = accounts,
        initialNotificationSettings = settings(ACCOUNT_A, nativeEnabled, localEnabled = initialLocalEnabled),
        nativePushFallbackPlatform = platform,
        onUpsertPushRegistration = onUpsert,
        onSetNativePushEnabled = onSetNative,
        onClearPushRegistration = onClear,
        onNotificationSettings = onNotificationSettings,
        pushServerConfigProvider = { CONFIG },
        nativePushCapabilityResolver = {
            if (fcmAvailable) NativePushCapability.Available else NativePushCapability.MissingPushServerConfiguration
        },
    )

    private suspend fun awaitStart(
        fixture: NotificationBootstrapTestFixture,
        platform: RecordingNativePushFallbackPlatform,
    ) {
        fixture.runWithMainLooperPumping {
            withTimeout(2_000L) {
                while (platform.starts.isEmpty()) yield()
            }
        }
    }

    /** Replays the existing runtime-publication fence without deleting any account data. */
    private fun replaceRuntimeOwner(appState: WhiteNoiseAppState) {
        val state =
            DestructiveAccountWipeRuntimeState(
                activeAccountRef = appState.activeAccountRef,
                activeConversationAccountRef = null,
                activeConversationGroupIdHex = null,
                runtimeGeneration = appState.runtimeGeneration + 1,
            )
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
            .apply { isAccessible = true }
            .invoke(appState, state)
    }

    /** Reads the delivery intent generation so the race test waits for actual supersession. */
    private fun notificationDeliveryModeGeneration(appState: WhiteNoiseAppState): Long =
        WhiteNoiseAppState::class.java.getDeclaredField("notificationDeliveryModeIntent").let { field ->
            field.isAccessible = true
            (field.get(appState) as StalenessGuard).capture()
        }

    private fun account(ref: String) = AccountSummaryFfi(ref, "$ref-id", true, false, false, true)

    private fun settings(
        ref: String,
        nativeEnabled: Boolean,
        localEnabled: Boolean = true,
    ) = NotificationSettingsFfi(ref, "$ref-id", localEnabled, nativeEnabled)

    /** Successful zero-group cleanup result for held registration-clear boundaries. */
    private fun completedPushRegistrationClear() =
        PushRegistrationShareOutcomeFfi(
            status = PushRegistrationShareStatusFfi.COMPLETE,
            attemptedGroups = 0u,
            succeededGroups = 0u,
            failedGroups = 0u,
            pendingGroups = 0u,
        )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        val CONFIG = PushServerConfig("0".repeat(64), null)
    }
}
