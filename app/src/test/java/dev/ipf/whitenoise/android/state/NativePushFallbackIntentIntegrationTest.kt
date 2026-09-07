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
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises explicit delivery intent and service-lifecycle invalidation against suspended fallback work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativePushFallbackIntentIntegrationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    /** Isolates durable transport state before every suspended race. */
    @Before
    fun resetPersistentFallback() {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        BackgroundConnectionPreferences.setEnabledDurably(context, false)
        PushTokenStore.create(context).apply {
            clearPending(ACCOUNT)
            clearPendingDisable(ACCOUNT)
            clearPendingNativePushRegistrationSync()
        }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** A user-owned background-off write wins durably when fallback persistence returns late. */
    @Test
    fun explicitBackgroundOffWinsAgainstHeldFallbackCommit() =
        runBlocking {
            val persistStarted = CountDownLatch(1)
            val releasePersist = CountDownLatch(1)
            val platform =
                RecordingNativePushFallbackPlatform(
                    context = context,
                    beforePersist = {
                        persistStarted.countDown()
                        releasePersist.await()
                    },
                )
            val fixture = fixture(platform = platform)
            var sync: Job? = null
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings())
                val syncJob = async { fixture.appState.syncNativePushRegistrationIfEnabled() }
                sync = syncJob
                await(fixture, persistStarted)

                assertTrue(fixture.appState.setBackgroundConnectionEnabled(false))
                releasePersist.countDown()
                assertFalse(syncJob.await())

                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
                assertFalse(fixture.appState.backgroundConnectionEnabled)
                assertTrue(platform.starts.isEmpty())
                assertTrue(fixture.nativePushSettingWrites.isEmpty())
            } finally {
                releasePersist.countDown()
                closeFixtureAfterJobs(fixture, sync)
            }
        }

    /** A fallback commit that turns stale after disk I/O durably restores the superseding explicit value. */
    @Test
    fun explicitPreferenceWriteAfterFallbackCommitWinsDurably() {
        var ownershipChecks = 0

        val committed =
            BackgroundConnectionPreferences.setEnabledDurablyIf(context, true) {
                if (ownershipChecks++ == 0) {
                    true
                } else {
                    BackgroundConnectionPreferences.setEnabled(context, false)
                    false
                }
            }

        assertFalse(committed)
        assertFalse(BackgroundConnectionPreferences.isEnabled(context))
    }

    /** A successful local-notification opt-out rejects a settings read captured before that intent. */
    @Test
    fun localNotificationOffRejectsAHeldStaleSettingsRead() =
        runBlocking {
            val readStarted = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val holdRead = AtomicBoolean(false)
            val staleSettings = notificationSettings()
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(
                    platform = platform,
                    onNotificationSettings = {
                        if (holdRead.get()) {
                            readStarted.countDown()
                            releaseRead.await()
                            staleSettings
                        } else {
                            notificationSettings(nativePushEnabled = false)
                        }
                    },
                )
            var sync: Job? = null
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings())
                holdRead.set(true)
                val syncJob = async { fixture.appState.syncNativePushRegistrationIfEnabled() }
                sync = syncJob
                await(fixture, readStarted)

                assertTrue(fixture.appState.setLocalNotificationsEnabled(false))
                releaseRead.countDown()
                assertFalse(syncJob.await())

                assertFalse(fixture.notificationSettings(ACCOUNT).localNotificationsEnabled)
                assertFalse(fixture.appState.localNotificationSettings?.localNotificationsEnabled == true)
                assertFalse(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(platform.starts.isEmpty())
                assertTrue(fixture.nativePushSettingWrites.isEmpty())
            } finally {
                releaseRead.countDown()
                closeFixtureAfterJobs(fixture, sync)
            }
        }

    /** Service loss during durable cleanup revokes readiness before the native-disable dispatch. */
    @Test
    fun unavailableRuntimeDuringPendingClearCannotDisableNativePush() =
        runBlocking {
            val clearStarted = CountDownLatch(1)
            val releaseClear = CountDownLatch(1)
            val platform =
                RecordingNativePushFallbackPlatform(
                    context = context,
                    beforeClear = {
                        clearStarted.countDown()
                        releaseClear.await()
                    },
                )
            val fixture = fixture(platform = platform)
            val tokenStore = PushTokenStore.create(context)
            var sync: Job? = null
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(notificationSettings())
                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())
                val generation = platform.starts.single()
                val syncJob =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(generation)
                        ?: error("fallback acknowledgement was not accepted")
                sync = syncJob
                await(fixture, clearStarted)

                fixture.rejectNativePushFallbackRuntime(generation)
                releaseClear.countDown()
                fixture.runWithMainLooperPumping { syncJob.join() }

                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                assertTrue(fixture.notificationSettings(ACCOUNT).nativePushEnabled)
                assertTrue(ACCOUNT in tokenStore.pendingClears())
                assertTrue(fixture.appState.backgroundConnectionEnabled)
            } finally {
                releaseClear.countDown()
                closeFixtureAfterJobs(fixture, sync)
            }
        }

    /** A known background requirement establishes fallback even when the active settings read fails. */
    @Test
    fun activeSettingsFailureStillStartsTheBackgroundAccountsFallback() =
        runBlocking {
            val failActiveRead = AtomicBoolean(false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val settingsByAccount =
                mapOf(
                    ACCOUNT to notificationSettings(localNotificationsEnabled = false, nativePushEnabled = false),
                    ACCOUNT_B to notificationSettings(ACCOUNT_B),
                )
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    accounts = listOf(account(), account(ACCOUNT_B)),
                    initialNotificationSettings = settingsByAccount.getValue(ACCOUNT),
                    onNotificationSettings = { accountRef ->
                        if (accountRef == ACCOUNT && failActiveRead.get()) {
                            throw IllegalStateException("active settings unavailable")
                        }
                        if (accountRef == ACCOUNT_B && !failActiveRead.get()) {
                            notificationSettings(ACCOUNT_B, nativePushEnabled = false)
                        } else {
                            settingsByAccount.getValue(accountRef)
                        }
                    },
                    nativePushFallbackPlatform = platform,
                )
            val tokenStore = PushTokenStore.create(context)
            try {
                fixture.bootstrap()
                val initialActiveSettings = fixture.appState.localNotificationSettings
                tokenStore.recordPendingNativePushRegistrationSync()
                failActiveRead.set(true)

                assertFalse(fixture.appState.syncNativePushRegistrationIfEnabled())

                assertTrue(BackgroundConnectionPreferences.isEnabled(context))
                assertTrue(fixture.appState.backgroundConnectionEnabled)
                assertEquals(1, platform.starts.size)
                assertEquals(initialActiveSettings, fixture.appState.localNotificationSettings)
                assertTrue(fixture.nativePushSettingWrites.isEmpty())
                assertTrue(tokenStore.nativePushRegistrationSyncPending())
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** Boots without migration so each test can arm its native preference and held boundary afterward. */
    private fun fixture(
        platform: NativePushFallbackPlatform,
        onNotificationSettings: ((String) -> NotificationSettingsFfi)? = null,
    ) = NotificationBootstrapTestFixture(
        context = context,
        accounts = listOf(account()),
        initialNotificationSettings = notificationSettings(nativePushEnabled = false),
        onNotificationSettings = onNotificationSettings,
        nativePushFallbackPlatform = platform,
    )

    /** Cancels suspended test work before disposing its AppState and service owners. */
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

    /** Waits without blocking the Robolectric Main owner needed by public AppState calls. */
    private suspend fun await(
        fixture: NotificationBootstrapTestFixture,
        latch: CountDownLatch,
    ) {
        fixture.runWithMainLooperPumping {
            withTimeout(2_000L) {
                while (latch.count > 0L) yield()
            }
        }
    }

    /** Builds the signed-in account used by the production all-account sweep. */
    private fun account(accountRef: String = ACCOUNT) =
        AccountSummaryFfi(
            label = accountRef,
            accountIdHex = if (accountRef == ACCOUNT) "self" else "other",
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** Builds the local/native-enabled setting that requires capability fallback. */
    private fun notificationSettings(
        accountRef: String = ACCOUNT,
        localNotificationsEnabled: Boolean = true,
        nativePushEnabled: Boolean = true,
    ) = NotificationSettingsFfi(
        accountRef = accountRef,
        accountIdHex = if (accountRef == ACCOUNT) "self" else "other",
        localNotificationsEnabled = localNotificationsEnabled,
        nativePushEnabled = nativePushEnabled,
    )

    private companion object {
        const val ACCOUNT = "account-a"
        const val ACCOUNT_B = "account-b"
    }
}
