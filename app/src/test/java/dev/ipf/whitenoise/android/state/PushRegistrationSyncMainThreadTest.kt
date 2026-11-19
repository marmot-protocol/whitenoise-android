package dev.ipf.whitenoise.android.state

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.PushRegistrationShareOutcomeFfi
import dev.ipf.marmotkit.PushRegistrationShareStatusFfi
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.KEY_PENDING_CLEARS
import dev.ipf.whitenoise.android.notifications.KEY_PENDING_DISABLES
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenKeyRule
import dev.ipf.whitenoise.android.notifications.PushTokenStore
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

/**
 * The push registration sync is started on the main thread. A push state write is a Keystore round
 * trip and a read can wait behind a background writer, so the sync does both on the storage
 * dispatcher. Each test fails when one of those hops is removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PushRegistrationSyncMainThreadTest {
    @get:Rule
    val pushTokenKey = PushTokenKeyRule()

    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    /** Starts every test with delivery paused and the notification permission granted. */
    @Before
    fun resetDeliveryState() {
        BackgroundConnectionPreferences.setEnabledDurably(context, false)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Stops the foreground service a fallback test may have started. */
    @After
    fun stopTestService() {
        NotificationStreamForegroundService.stop(context)
    }

    /** Draining a pending clear rewrites the push state on the storage dispatcher, never on the main thread. */
    @Test
    fun pendingClearDrainSealsOffTheMainThread() =
        assertDrainSealsOffTheMainThread(
            seed = { it.recordPendingClear(ACCOUNT_A) },
            drained = { it.pendingClears().isEmpty() },
        )

    /** Draining a pending disable rewrites the push state on the storage dispatcher, never on the main thread. */
    @Test
    fun pendingDisableDrainSealsOffTheMainThread() =
        assertDrainSealsOffTheMainThread(
            seed = { it.recordPendingDisable(ACCOUNT_A) },
            drained = { it.pendingDisables().isEmpty() },
        )

    /** Runs the paused-delivery drains for one seeded obligation and counts Keystore requests until it is gone. */
    private fun assertDrainSealsOffTheMainThread(
        seed: (PushTokenStore) -> Boolean,
        drained: (PushTokenStore) -> Boolean,
    ) = runBlocking {
        val seals = countSeals()
        val fixture = fixture(NativePushCapability.MissingPushServerConfiguration, nativeEnabled = false)
        try {
            fixture.bootstrap()
            fixture.replaceNotificationSettings(settings(nativeEnabled = true))
            fixture.appState.refreshLocalNotificationSettings()
            assertTrue(seed(PushTokenStore.create(context)))
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            seals.armed.set(true)

            fixture.appState.setAppInForeground(true)
            fixture.runWithMainLooperPumping {
                withTimeout(2_000L) {
                    while (!drained(PushTokenStore.create(context))) yield()
                }
            }
            seals.armed.set(false)

            seals.assertSealedOnlyOffTheMainThread()
        } finally {
            fixture.appState.setAppInForeground(false)
            fixture.close()
        }
    }

    /** A registration sync on a device without native push completes without a Keystore request on the main thread. */
    @Test
    fun unavailableCapabilitySyncSealsOffTheMainThread() =
        runBlocking {
            val seals = countSeals()
            val fixture = fixture(NativePushCapability.MissingPushServerConfiguration, nativeEnabled = false)
            try {
                fixture.bootstrap()
                seals.armed.set(true)

                val complete = fixture.runOnMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() }
                seals.armed.set(false)

                assertTrue(complete)
                seals.assertSealedOnlyOffTheMainThread()
            } finally {
                fixture.close()
            }
        }

    /** Rolling back a registration that lost to a concurrent disable clears its pending entry off the main thread. */
    @Test
    fun registrationRollbackSealsOffTheMainThread() =
        runBlocking {
            val seals = countSeals()
            val live = AtomicBoolean(false)
            val upserted = AtomicBoolean(false)
            val clearCalls = AtomicInteger()
            val fixture =
                fixture(
                    capability = NativePushCapability.Available,
                    nativeEnabled = false,
                    onUpsert = { if (live.get()) upserted.set(true) },
                    // Enabled until the upsert lands, so the re-read after it sees a disable that won.
                    onNotificationSettings = { settings(nativeEnabled = live.get() && !upserted.get()) },
                    onClear = {
                        // Fail the drain's attempt so the entry is still pending when the rollback clears it.
                        if (live.get() && clearCalls.getAndIncrement() == 0) error("drain clear fails once")
                        completedPushRegistrationClear()
                    },
                )
            try {
                fixture.bootstrap()
                assertTrue(PushTokenStore.create(context).recordPendingClear(ACCOUNT_A))
                live.set(true)
                seals.armed.set(true)

                val complete = fixture.runOnMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() }
                seals.armed.set(false)

                assertTrue(complete)
                assertTrue(upserted.get())
                assertEquals(2, clearCalls.get())
                assertTrue(PushTokenStore.create(context).pendingClears().isEmpty())
                seals.assertSealedOnlyOffTheMainThread()
            } finally {
                fixture.close()
            }
        }

    /** The clear made once the fallback service owns delivery rewrites the push state off the main thread. */
    @Test
    fun fallbackOwnerClearSealsOffTheMainThread() =
        runBlocking {
            val seals = countSeals()
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(NativePushCapability.MissingPushServerConfiguration, nativeEnabled = false, platform = platform)
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(nativeEnabled = true))
                assertFalse(fixture.runOnMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })
                seals.armed.set(true)

                fixture.acknowledgeNativePushFallbackRuntime(platform.starts.last())
                seals.armed.set(false)

                assertEquals(listOf(ACCOUNT_A), fixture.clearedPushRegistrations.toList())
                assertTrue(PushTokenStore.create(context).pendingClears().isEmpty())
                seals.assertSealedOnlyOffTheMainThread()
            } finally {
                fixture.close()
            }
        }

    /** A registration sync reads the pending sets on the storage dispatcher, in the drains and per account. */
    @Test
    fun registrationSyncReadsPushStateOffTheMainThread() =
        runBlocking {
            val access = PushStateAccessRecorder(Thread.currentThread())
            val preferences = context.getSharedPreferences("push-sync-main-thread-test", Context.MODE_PRIVATE)
            val store = PushTokenStore(access.wrap(preferences))
            val fixture = fixture(NativePushCapability.Available, nativeEnabled = true)
            try {
                fixture.bootstrap()
                fixture.replacePushTokenStore(store)
                assertTrue(store.recordPendingClear(ACCOUNT_B))
                assertTrue(store.recordPendingDisable(ACCOUNT_B))
                access.armed.set(true)

                val complete = fixture.runOnMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() }
                access.armed.set(false)

                assertTrue(complete)
                // Both drains ran. How often the store rereads a set while removing an entry is its own business.
                assertEquals(
                    listOf(KEY_PENDING_CLEARS, KEY_PENDING_DISABLES),
                    access.keys.filter { it == KEY_PENDING_CLEARS || it == KEY_PENDING_DISABLES }.distinct(),
                )
                assertEquals(emptyList<String>(), access.onMainThread.toList())
            } finally {
                fixture.close()
            }
        }

    /** Counts key requests from here on without changing the key the rule installed. */
    private fun countSeals(): MainThreadSealCounter {
        val seals = MainThreadSealCounter(Thread.currentThread(), pushTokenKey.provider)
        PushTokenStore.installKeyProvider(seals)
        return seals
    }

    /** Builds a one-account fixture whose native push capability is fixed to [capability]. */
    private fun fixture(
        capability: NativePushCapability,
        nativeEnabled: Boolean,
        platform: NativePushFallbackPlatform = RecordingNativePushFallbackPlatform(context),
        onUpsert: (String) -> Unit = {},
        onNotificationSettings: ((String) -> NotificationSettingsFfi)? = null,
        onClear: ((String) -> PushRegistrationShareOutcomeFfi)? = null,
    ) = NotificationBootstrapTestFixture(
        context = context,
        accounts = listOf(AccountSummaryFfi(ACCOUNT_A, ACCOUNT_A_ID, true, false, false, true, false)),
        initialNotificationSettings = settings(nativeEnabled),
        nativePushFallbackPlatform = platform,
        onUpsertPushRegistration = onUpsert,
        onClearPushRegistration = onClear,
        onNotificationSettings = onNotificationSettings,
        pushServerConfigProvider = { PushServerConfig("0".repeat(64), null) },
        fcmTokenReader = { "test-token" },
        nativePushCapabilityResolver = { capability },
    )

    /** Notification settings for the fixture account with local notifications on. */
    private fun settings(nativeEnabled: Boolean) = NotificationSettingsFfi(ACCOUNT_A, ACCOUNT_A_ID, true, nativeEnabled)

    /** Successful zero-group result for a registration clear. */
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
        const val ACCOUNT_A_ID = "account-a-id"
        const val ACCOUNT_B = "account-b"
    }
}

/**
 * Counts Keystore key requests made after arming, and separately those made on [mainThread]. The key
 * comes from [delegate], so a payload sealed before this counter was installed still opens.
 */
private class MainThreadSealCounter(
    private val mainThread: Thread,
    private val delegate: SecureStoreKeyProvider,
) : SecureStoreKeyProvider {
    val armed = AtomicBoolean(false)
    private val total = AtomicInteger()
    private val onMainThread = AtomicInteger()

    /** Hands out the delegate's key, counting the request once armed. */
    override fun secretKey(): SecretKey {
        if (armed.get()) {
            total.incrementAndGet()
            if (Thread.currentThread() === mainThread) onMainThread.incrementAndGet()
        }
        return delegate.secretKey()
    }

    /** Requires that the armed window reached the key, and never from the main thread. */
    fun assertSealedOnlyOffTheMainThread() {
        assertTrue(total.get() > 0)
        assertEquals(0, onMainThread.get())
    }
}

/** Records which push state keys are touched after arming, and which calls arrive on [mainThread]. */
private class PushStateAccessRecorder(
    private val mainThread: Thread,
) {
    val armed = AtomicBoolean(false)
    val keys = CopyOnWriteArrayList<String>()
    val onMainThread = CopyOnWriteArrayList<String>()

    /** Wraps [preferences] so reads are seen too, a cached read never reaches the key a seal counter watches. */
    fun wrap(preferences: SharedPreferences): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) {
            _,
            method,
            arguments,
            ->
            val key = arguments?.firstOrNull() as? String
            if (armed.get()) {
                key?.let(keys::add)
                if (Thread.currentThread() === mainThread) onMainThread += "${method.name}(${key.orEmpty()})"
            }
            try {
                method.invoke(preferences, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as SharedPreferences
}
