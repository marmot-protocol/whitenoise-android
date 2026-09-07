package dev.ipf.whitenoise.android.state

import android.Manifest
import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativePushCapabilityFallbackIntegrationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    /** Isolates durable delivery preferences and retries between real AppState fixture cases. */
    @Before
    fun resetPersistentFallback() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        BackgroundConnectionPreferences.setEnabledDurably(context, false)
        PushTokenStore.create(context).apply {
            clearPending(ACCOUNT_A)
            clearPending(ACCOUNT_B)
            clearPendingDisable(ACCOUNT_A)
            clearPendingDisable(ACCOUNT_B)
            clearPendingNativePushRegistrationSync()
        }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** The public sync migrates a previously usable native path without leaving either delivery mode off. */
    @Test
    fun publicSyncReconcilesPersistedNativePushAfterBuildCapabilityLoss() =
        runBlocking {
            val platform = fallbackPlatform()
            val fixture = fixture(localNotificationsEnabled = true, platform = platform)
            val tokenStore = PushTokenStore.create(context)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))
                tokenStore.recordPendingNativePushRegistrationSync()
                assertFalse(fixture.appState.nativePushCapability().isAvailable)

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                assertEquals(1, platform.starts.size)
                assertTrue(tokenStore.nativePushRegistrationSyncPending())

                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.single())

                assertEquals(listOf("account-a" to false), fixture.nativePushSettingWrites)
                assertEquals(listOf("account-a"), fixture.clearedPushRegistrations)
                assertFalse(fixture.appState.localNotificationSettings?.nativePushEnabled == true)
                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(tokenStore.nativePushRegistrationSyncPending())

                assertTrue(fixture.appState.syncNativePushRegistrationIfEnabled())
                assertEquals("a reconciled preference must be idempotent", 1, fixture.nativePushSettingWrites.size)
                assertEquals("a reconciled registration must clear once", 1, fixture.clearedPushRegistrations.size)
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** Turning local notifications off remains authoritative even if an old native preference is still true. */
    @Test
    fun userDisabledLocalNotificationsDoNotEnablePersistentFallback() =
        runBlocking {
            val platform = fallbackPlatform()
            val fixture = fixture(localNotificationsEnabled = false, platform = platform)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", false))

                assertTrue(fixture.appState.syncNativePushRegistrationIfEnabled())

                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                assertTrue(fixture.clearedPushRegistrations.isEmpty())
                assertTrue(platform.starts.isEmpty())
                assertEquals(0, platform.persistCalls)
                assertFalse(fixture.appState.backgroundConnectionEnabled)
                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** Background native-push state stays isolated and cannot silently turn the active account's notifications on. */
    @Test
    fun backgroundNativePushRequirementDoesNotReverseTheActiveAccountsOptOut() =
        runBlocking {
            val platform = fallbackPlatform()
            val fixture =
                fixture(
                    localNotificationsEnabled = false,
                    otherSettings = notificationSettings(ACCOUNT_B, localNotificationsEnabled = true),
                    platform = platform,
                )
            val tokenStore = PushTokenStore.create(context)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", false))
                tokenStore.recordPendingNativePushRegistrationSync()

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.distinct().single())

                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                assertTrue(fixture.clearedPushRegistrations.isEmpty())
                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_A).localNotificationsEnabled)
                assertTrue(tokenStore.nativePushRegistrationSyncPending())
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** The active account is repaired first while an enabled background account keeps global readiness false. */
    @Test
    fun backgroundNativePushRequirementDoesNotBlockTheActiveFallback() =
        runBlocking {
            val platform = fallbackPlatform()
            val fixture =
                fixture(
                    localNotificationsEnabled = true,
                    otherSettings = notificationSettings(ACCOUNT_B, localNotificationsEnabled = true),
                    platform = platform,
                )
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.distinct().single())

                assertEquals(listOf(ACCOUNT_A to false), fixture.nativePushSettingWrites)
                assertEquals(listOf(ACCOUNT_A), fixture.clearedPushRegistrations)
                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** A native-disable failure keeps persistent delivery and a durable clear retry without publishing false state. */
    @Test
    fun nativeDisableFailureKeepsFallbackAndCleanupRetryable() =
        runBlocking {
            val platform = fallbackPlatform()
            val fixture =
                fixture(
                    localNotificationsEnabled = true,
                    platform = platform,
                    onSetNativePushEnabled = { _, _ -> throw IllegalStateException("native disable unavailable") },
                )
            val tokenStore = PushTokenStore.create(context)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.single())

                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertEquals(setOf(ACCOUNT_A), tokenStore.pendingClears())
                assertTrue(fixture.clearedPushRegistrations.isEmpty())
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** A cancelled return from a committed native disable leaves durable cleanup for the next sync. */
    @Test
    fun lateDisableCancellationRetainsAndDrainsRegistrationCleanup() =
        runBlocking {
            val disableStarted = CountDownLatch(1)
            val releaseDisable = CountDownLatch(1)
            val platform = fallbackPlatform()
            val fixture =
                fixture(
                    localNotificationsEnabled = true,
                    platform = platform,
                    onSetNativePushEnabled = { accountRef, enabled ->
                        disableStarted.countDown()
                        releaseDisable.await()
                        notificationSettings(
                            accountRef = accountRef,
                            accountIdHex = "self",
                            localNotificationsEnabled = true,
                            nativePushEnabled = enabled,
                        )
                    },
                )
            val tokenStore = PushTokenStore.create(context)
            var sync: Job? = null
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))
                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                val syncJob =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.single())
                        ?: error("fallback acknowledgement was not accepted")
                sync = syncJob
                fixture.runWithMainLooperPumping {
                    withTimeout(2_000L) {
                        while (disableStarted.count > 0L) yield()
                    }
                }

                syncJob.cancel()
                releaseDisable.countDown()
                fixture.runWithMainLooperPumping { syncJob.cancelAndJoin() }

                assertEquals(setOf(ACCOUNT_A), tokenStore.pendingClears())
                assertTrue(fixture.clearedPushRegistrations.isEmpty())
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)

                assertTrue(fixture.appState.syncNativePushRegistrationIfEnabled())
                assertEquals(listOf(ACCOUNT_A), fixture.clearedPushRegistrations)
                assertTrue(tokenStore.pendingClears().isEmpty())
            } finally {
                releaseDisable.countDown()
                closeFixtureAfterJobs(fixture, sync)
            }
        }

    /** An A-to-B-to-A switch rejects a late native result and leaves its cleanup owned by the next sync. */
    @Test
    fun rapidAccountReturnCannotPublishOrClearFromTheSupersededGeneration() =
        runBlocking {
            val disableStarted = CountDownLatch(1)
            val releaseDisable = CountDownLatch(1)
            val platform = fallbackPlatform()
            val fixture =
                fixture(
                    localNotificationsEnabled = true,
                    otherSettings =
                        notificationSettings(
                            ACCOUNT_B,
                            localNotificationsEnabled = true,
                            nativePushEnabled = false,
                        ),
                    platform = platform,
                    onSetNativePushEnabled = { accountRef, enabled ->
                        disableStarted.countDown()
                        releaseDisable.await()
                        notificationSettings(accountRef, "self", true, nativePushEnabled = enabled)
                    },
                )
            val tokenStore = PushTokenStore.create(context)
            var sync: Job? = null
            var switchToB: Job? = null
            var switchBackToA: Job? = null
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))
                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                val syncJob =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.single())
                        ?: error("fallback acknowledgement was not accepted")
                sync = syncJob
                fixture.runWithMainLooperPumping {
                    withTimeout(2_000L) {
                        while (disableStarted.count > 0L) yield()
                    }
                }

                val switchToBJob = async { fixture.appState.setActiveAccount(ACCOUNT_B) }
                switchToB = switchToBJob
                awaitActiveAccount(fixture, ACCOUNT_B)
                val switchBackToAJob = async { fixture.appState.setActiveAccount(ACCOUNT_A) }
                switchBackToA = switchBackToAJob
                awaitActiveAccount(fixture, ACCOUNT_A)
                fixture.runWithMainLooperPumping {
                    switchToBJob.cancelAndJoin()
                    switchBackToAJob.cancelAndJoin()
                }
                releaseDisable.countDown()

                fixture.runWithMainLooperPumping { syncJob.join() }
                assertTrue(fixture.appState.localNotificationSettings?.nativePushEnabled == true)
                assertTrue(fixture.clearedPushRegistrations.isEmpty())
                assertEquals(setOf(ACCOUNT_A), tokenStore.pendingClears())
            } finally {
                releaseDisable.countDown()
                closeFixtureAfterJobs(fixture, switchToB, switchBackToA, sync)
            }
        }

    /** A failed durable preference write leaves native enabled and is re-committed despite an in-memory true value. */
    @Test
    fun failedFallbackPreferenceCommitIsRetriedBeforeNativeDisable() =
        runBlocking {
            val platform = fallbackPlatform(persistResults = ArrayDeque(listOf(false, true, true)))
            val fixture = fixture(localNotificationsEnabled = true, platform = platform)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())

                assertEquals(1, platform.persistCalls)
                assertTrue(
                    "failed commit may still change the in-memory preference",
                    BackgroundConnectionPreferences.isEnabled(context),
                )
                assertFalse(fixture.appState.backgroundConnectionEnabled)
                assertTrue(platform.starts.isEmpty())
                assertTrue(fixture.nativePushSettingWrites.isEmpty())

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                assertEquals(2, platform.persistCalls)
                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.single())

                assertEquals(3, platform.persistCalls)
                assertEquals(listOf(ACCOUNT_A to false), fixture.nativePushSettingWrites)
            } finally {
                closeFixtureAfterJobs(fixture)
            }
        }

    /** A failed durable cleanup marker prevents the native mutation and retries the commit on the next sync. */
    @Test
    fun failedPendingClearCommitCannotDisableNativePush() =
        runBlocking {
            val platform = fallbackPlatform(clearResults = ArrayDeque(listOf(false, true)))
            val fixture = fixture(localNotificationsEnabled = true, platform = platform)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))
                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())

                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.single())

                assertEquals(1, platform.clearCalls)
                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)

                assertTrue(fixture.appState.syncNativePushRegistrationIfEnabled())
                assertEquals(2, platform.clearCalls)
                assertEquals(listOf(ACCOUNT_A to false), fixture.nativePushSettingWrites)
            } finally {
                closeFixtureAfterJobs(fixture)
            }
        }

    /** A rejected service generation stays unready; a stale callback cannot authorize the replacement request. */
    @Test
    fun rejectedFallbackStartRequiresANewAcknowledgedGeneration() =
        runBlocking {
            val platform = fallbackPlatform(startResults = ArrayDeque(listOf(false, true)))
            val fixture = fixture(localNotificationsEnabled = true, platform = platform)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                val rejectedGeneration = platform.starts.single()
                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                val acceptedGeneration = platform.starts.last()
                assertTrue(acceptedGeneration > rejectedGeneration)

                fixture.acknowledgeNativePushFallbackRuntime(rejectedGeneration)
                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                fixture.acknowledgeNativePushFallbackRuntime(acceptedGeneration)

                assertEquals(listOf(ACCOUNT_A to false), fixture.nativePushSettingWrites)
            } finally {
                closeFixtureAfterJobs(fixture)
            }
        }

    /** A service teardown invalidates its exact request and a stale callback cannot authorize the retry. */
    @Test
    fun unavailableFallbackRuntimeRequiresANewAcknowledgedGeneration() =
        runBlocking {
            val platform = fallbackPlatform()
            val fixture = fixture(localNotificationsEnabled = true, platform = platform)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                val unavailableGeneration = platform.starts.single()
                fixture.rejectNativePushFallbackRuntime(unavailableGeneration)

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                val replacementGeneration = platform.starts.last()
                assertTrue(replacementGeneration > unavailableGeneration)

                fixture.acknowledgeNativePushFallbackRuntime(unavailableGeneration)
                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                fixture.acknowledgeNativePushFallbackRuntime(replacementGeneration)

                assertEquals(listOf(ACCOUNT_A to false), fixture.nativePushSettingWrites)
            } finally {
                closeFixtureAfterJobs(fixture)
            }
        }

    /** A background settings failure keeps global readiness incomplete without blocking known active-account repair. */
    @Test
    fun backgroundSettingsFailureDoesNotBlockTheActiveFallback() =
        runBlocking {
            val platform = fallbackPlatform()
            val activeSettings = notificationSettings(ACCOUNT_A, "self", true)
            val fixture =
                fixture(
                    localNotificationsEnabled = true,
                    otherSettings = notificationSettings(ACCOUNT_B, localNotificationsEnabled = true),
                    platform = platform,
                    onNotificationSettings = { account ->
                        if (account == ACCOUNT_B) throw IllegalStateException("background account unavailable")
                        activeSettings
                    },
                )
            val tokenStore = PushTokenStore.create(context)
            try {
                fixture.bootstrap()
                tokenStore.recordPendingNativePushRegistrationSync()
                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())

                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.distinct().single())

                assertEquals(listOf(ACCOUNT_A to false), fixture.nativePushSettingWrites)
                assertTrue(tokenStore.nativePushRegistrationSyncPending())
                assertTrue(fixture.appState.backgroundConnectionEnabled)
            } finally {
                closeFixtureAfterJobs(fixture)
            }
        }

    /** Cancellation after a durable preference write cannot reach service start or native mutation. */
    @Test
    fun cancellationBeforeFallbackStartLeavesNativePushUntouched() =
        runBlocking {
            val persistStarted = CountDownLatch(1)
            val releasePersist = CountDownLatch(1)
            val platform =
                fallbackPlatform(
                    beforePersist = {
                        persistStarted.countDown()
                        releasePersist.await()
                    },
                )
            val fixture = fixture(localNotificationsEnabled = true, platform = platform)
            var sync: Job? = null
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings(ACCOUNT_A, "self", true))
                val syncJob = async { fixture.appState.syncNativePushRegistrationIfEnabled() }
                sync = syncJob
                withTimeout(2_000L) {
                    while (persistStarted.count > 0L) yield()
                }

                syncJob.cancel()
                releasePersist.countDown()
                syncJob.cancelAndJoin()

                assertTrue(platform.starts.isEmpty())
                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
            } finally {
                releasePersist.countDown()
                closeFixtureAfterJobs(fixture, sync)
            }
        }

    /** Waits for the real account-switch local-ready boundary without advancing post-activation work. */
    private suspend fun awaitActiveAccount(
        fixture: NotificationBootstrapTestFixture,
        accountRef: String,
    ) {
        fixture.runWithMainLooperPumping {
            withTimeout(2_000L) {
                while (fixture.appState.activeAccountRef != accountRef) yield()
            }
        }
    }

    /** Cancels test-owned jobs despite parent cancellation, then always closes fixture and service state. */
    private suspend fun closeFixtureAfterJobs(
        fixture: NotificationBootstrapTestFixture,
        vararg jobs: Job?,
    ) {
        try {
            withContext(NonCancellable) {
                fixture.runWithMainLooperPumping {
                    jobs.forEach { job -> job?.cancelAndJoin() }
                }
            }
        } finally {
            try {
                fixture.close()
            } finally {
                NotificationStreamForegroundService.stop(context)
            }
        }
    }

    /** Creates one real AppState/runtime fixture with an authoritative enabled native-push preference. */
    private fun fixture(
        localNotificationsEnabled: Boolean,
        otherSettings: NotificationSettingsFfi? = null,
        platform: NativePushFallbackPlatform = fallbackPlatform(),
        onSetNativePushEnabled: ((accountRef: String, enabled: Boolean) -> NotificationSettingsFfi)? = null,
        onNotificationSettings: ((accountRef: String) -> NotificationSettingsFfi)? = null,
    ): NotificationBootstrapTestFixture =
        NotificationBootstrapTestFixture(
            context = context,
            accounts =
                buildList {
                    add(account(ACCOUNT_A, "self"))
                    if (otherSettings != null) add(account(ACCOUNT_B, "other"))
                },
            initialNotificationSettings =
                notificationSettings(
                    accountRef = ACCOUNT_A,
                    accountIdHex = "self",
                    localNotificationsEnabled = localNotificationsEnabled,
                    nativePushEnabled = false,
                ),
            onSetNativePushEnabled = onSetNativePushEnabled,
            onNotificationSettings = onNotificationSettings,
            nativePushFallbackPlatform = platform,
        ).also { fixture -> otherSettings?.let(fixture::replaceNotificationSettings) }

    /** Creates a deterministic persistence/service boundary backed by the real durable stores on success. */
    private fun fallbackPlatform(
        persistResults: ArrayDeque<Boolean> = ArrayDeque(),
        startResults: ArrayDeque<Boolean> = ArrayDeque(),
        clearResults: ArrayDeque<Boolean> = ArrayDeque(),
        beforePersist: () -> Unit = {},
        beforeClear: () -> Unit = {},
    ) = RecordingNativePushFallbackPlatform(
        context = context,
        persistResults = persistResults,
        startResults = startResults,
        clearResults = clearResults,
        beforePersist = beforePersist,
        beforeClear = beforeClear,
    )

    /** Builds a signed-in account that participates in the production push sweep. */
    private fun account(
        accountRef: String,
        accountIdHex: String,
    ) = AccountSummaryFfi(
        label = accountRef,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /** Builds one authoritative native-push preference for the requested account. */
    private fun notificationSettings(
        accountRef: String,
        accountIdHex: String = "other",
        localNotificationsEnabled: Boolean,
        nativePushEnabled: Boolean = true,
    ) = NotificationSettingsFfi(
        accountRef = accountRef,
        accountIdHex = accountIdHex,
        localNotificationsEnabled = localNotificationsEnabled,
        nativePushEnabled = nativePushEnabled,
    )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
    }
}
