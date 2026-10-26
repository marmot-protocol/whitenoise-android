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

    /** A current Firebase callback remains usable when a redundant SDK read fails. */
    @Test
    fun freshCallbackRegistersWithoutSuccessfulSecondSdkRead() =
        runBlocking {
            var unavailable = false
            val fixture = fixture { if (unavailable) error("unavailable") else "bootstrap-token" }
            try {
                fixture.bootstrap()
                fixture.appState.syncNativePushRegistrationIfEnabled()
                fixture.upsertedPushTokens.clear()
                unavailable = true
                val callback = checkNotNull(fixture.appState.onPushTokenRotated("callback-token"))
                fixture.runWithMainLooperPumping { callback.join() }
                assertEquals(listOf("callback-token"), fixture.upsertedPushTokens.toList())
                assertFalse(PushTokenStore.create(context).nativePushRegistrationSyncPending())
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** A later callback owns queued registration; older work cannot acknowledge its pending flag. */
    @Test
    fun onlyLatestQueuedCallbackRegisters() =
        runBlocking {
            var unavailable = false
            val fixture = fixture { if (unavailable) error("unavailable") else "bootstrap-token" }
            try {
                fixture.bootstrap()
                fixture.appState.syncNativePushRegistrationIfEnabled()
                fixture.upsertedPushTokens.clear()
                unavailable = true
                val mutex = deliveryMutex(fixture)
                mutex.lock()
                try {
                    val first = checkNotNull(fixture.appState.onPushTokenRotated("first-callback"))
                    val latest = checkNotNull(fixture.appState.onPushTokenRotated("latest-callback"))
                    mutex.unlock()
                    fixture.runWithMainLooperPumping {
                        first.join()
                        latest.join()
                    }
                } finally {
                    if (mutex.isLocked) mutex.unlock()
                }
                assertEquals(listOf("latest-callback"), fixture.upsertedPushTokens.toList())
                assertFalse(PushTokenStore.create(context).nativePushRegistrationSyncPending())
            } finally {
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** SDK success can supersede the callback, but callback authority cannot survive owner changes. */
    @Test
    fun callbackPrefersNewerSdkTokenAndRejectsRetiredOwners() =
        runBlocking {
            for (retirement in listOf("none", "runtime", "account", "signout", "wipe", "clear")) {
                var token = "bootstrap-token"
                val fixture = fixture { token }
                try {
                    fixture.bootstrap()
                    fixture.appState.syncNativePushRegistrationIfEnabled()
                    fixture.upsertedPushTokens.clear()
                    token = "newer-sdk-token"
                    val mutex = deliveryMutex(fixture)
                    mutex.lock()
                    val callback = checkNotNull(fixture.appState.onPushTokenRotated("callback-token"))
                    retirePushOwner(fixture, retirement)
                    mutex.unlock()
                    fixture.runWithMainLooperPumping { callback.join() }
                    if (retirement == "none") {
                        assertEquals(listOf("newer-sdk-token"), fixture.upsertedPushTokens.toList())
                    } else {
                        assertTrue(retirement, fixture.upsertedPushTokens.isEmpty())
                    }
                } finally {
                    fixture.appState.signOutInProgress = false
                    fixture.appState.wipeInProgress = false
                    fixture.close()
                    NotificationStreamForegroundService.stop(context)
                }
            }
        }

    /** Unconfirmed cleanup still blocks a fresh event, even though it was durably recorded. */
    @Test
    fun callbackCannotBypassUnknownStorageCleanup() =
        runBlocking {
            var unavailable = false
            val fixture = fixture { if (unavailable) error("unavailable") else "bootstrap-token" }
            try {
                fixture.bootstrap()
                fixture.appState.syncNativePushRegistrationIfEnabled()
                fixture.upsertedPushTokens.clear()
                unavailable = true
                val blocked =
                    PushTokenStore(
                        context.getSharedPreferences("callback-cleanup-test", android.content.Context.MODE_PRIVATE),
                        recoveryStatus = {
                            dev.ipf.whitenoise.android.notifications.PushStorageRecovery.StorageUnavailable
                        },
                    )
                fixture.replacePushTokenStore(blocked)
                val callback =
                    fixture.runWithMainLooperPumping {
                        // Keep the handle after the blocked rotation has completed and detached from its parent.
                        val rotation = checkNotNull(fixture.appState.onPushTokenRotated("callback-token"))
                        rotation.join()
                        rotation
                    }
                fixture.runWithMainLooperPumping { callback.join() }
                assertTrue(fixture.upsertedPushTokens.isEmpty())
                assertTrue(blocked.nativePushRegistrationSyncPending())
            } finally {
                fixture.close()
                context.deleteSharedPreferences("callback-cleanup-test")
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** Suspended SDK work cannot outlive its callback, runtime or account owner. */
    @Test
    fun suspendedSdkReadRejectsSupersededCallbackAndRetiredOwners() =
        runBlocking {
            for (retirement in listOf("rotation", "runtime", "account")) {
                var rotating = false
                val started = kotlinx.coroutines.CompletableDeferred<Unit>()
                val release = kotlinx.coroutines.CompletableDeferred<Unit>()
                val reads =
                    java.util.concurrent.atomic
                        .AtomicInteger()
                val fixture =
                    fixture {
                        if (!rotating) {
                            "bootstrap-token"
                        } else if (reads.incrementAndGet() == 1) {
                            started.complete(Unit)
                            release.await()
                            "superseded-sdk-token"
                        } else {
                            error("unavailable")
                        }
                    }
                try {
                    fixture.bootstrap()
                    fixture.appState.syncNativePushRegistrationIfEnabled()
                    fixture.upsertedPushTokens.clear()
                    rotating = true
                    val first = checkNotNull(fixture.appState.onPushTokenRotated("first-callback"))
                    fixture.runWithMainLooperPumping {
                        kotlinx.coroutines.withTimeout(5_000L) { started.await() }
                        val latest =
                            if (retirement == "rotation") {
                                checkNotNull(fixture.appState.onPushTokenRotated("latest-callback"))
                            } else {
                                retirePushOwner(fixture, retirement)
                                null
                            }
                        release.complete(Unit)
                        first.join()
                        latest?.join()
                        if (latest != null) awaitRegistrationSettled()
                    }
                    if (retirement == "rotation") {
                        assertEquals(listOf("latest-callback"), fixture.upsertedPushTokens.toList())
                        assertEquals("latest-callback", PushTokenStore.create(context).lastToken())
                        assertFalse(PushTokenStore.create(context).nativePushRegistrationSyncPending())
                    } else {
                        assertTrue(retirement, fixture.upsertedPushTokens.isEmpty())
                        assertEquals("first-callback", PushTokenStore.create(context).lastToken())
                        assertTrue(PushTokenStore.create(context).nativePushRegistrationSyncPending())
                    }
                } finally {
                    release.complete(Unit)
                    fixture.close()
                    NotificationStreamForegroundService.stop(context)
                }
            }
        }

    /** A callback arriving inside an older SDK write must win the shared store lock before registration. */
    @Test
    fun sdkPersistenceAndConcurrentCallbackStayOrdered() =
        runBlocking {
            var rotating = false
            val enteredWrite = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseWrite = java.util.concurrent.CountDownLatch(1)
            val reads =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val fixture =
                fixture {
                    if (!rotating) {
                        "bootstrap-token"
                    } else if (reads.incrementAndGet() == 1) {
                        "older-sdk-token"
                    } else {
                        error("unavailable")
                    }
                }
            val store = PushTokenStore(heldSdkPreferences(enteredWrite, releaseWrite))
            try {
                fixture.bootstrap()
                fixture.appState.syncNativePushRegistrationIfEnabled()
                fixture.upsertedPushTokens.clear()
                store.setToken("bootstrap-token")
                fixture.replacePushTokenStore(store)
                rotating = true
                val first = checkNotNull(fixture.appState.onPushTokenRotated("first-callback"))
                fixture.runWithMainLooperPumping {
                    kotlinx.coroutines.coroutineScope {
                        kotlinx.coroutines.withTimeout(5_000L) { enteredWrite.await() }
                        val latest =
                            async(kotlinx.coroutines.Dispatchers.IO) {
                                checkNotNull(fixture.appState.onPushTokenRotated("latest-callback"))
                            }
                        val sequence =
                            WhiteNoiseAppState::class.java
                                .getDeclaredField("pushTokenRotationSequence")
                                .apply { isAccessible = true }
                                .get(fixture.appState) as java.util.concurrent.atomic.AtomicLong
                        kotlinx.coroutines.withTimeout(5_000L) {
                            while (sequence.get() < 2L) kotlinx.coroutines.yield()
                        }
                        releaseWrite.countDown()
                        first.join()
                        latest.await().join()
                        kotlinx.coroutines.withTimeout(5_000L) {
                            while (store.nativePushRegistrationSyncPending()) kotlinx.coroutines.yield()
                        }
                    }
                }
                assertEquals(listOf("latest-callback"), fixture.upsertedPushTokens.toList())
                assertEquals("latest-callback", store.lastToken())
                assertFalse(store.nativePushRegistrationSyncPending())
            } finally {
                releaseWrite.countDown()
                fixture.close()
                context.deleteSharedPreferences("callback-atomic-test")
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** An ordinary query suspended before a fresh callback cannot replace it or consume its retry marker. */
    @Test
    fun ordinarySdkReadCannotReplaceFreshCallback() =
        runBlocking {
            var rotating = false
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val reads =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val fixture =
                fixture {
                    if (!rotating) {
                        "bootstrap-token"
                    } else if (reads.incrementAndGet() == 1) {
                        started.complete(Unit)
                        release.await()
                        "older-sdk-token"
                    } else {
                        error("unavailable")
                    }
                }
            try {
                fixture.bootstrap()
                fixture.appState.syncNativePushRegistrationIfEnabled()
                fixture.upsertedPushTokens.clear()
                rotating = true
                fixture.runWithMainLooperPumping {
                    kotlinx.coroutines.coroutineScope {
                        val ordinary = async { fixture.appState.syncNativePushRegistrationIfEnabled() }
                        kotlinx.coroutines.withTimeout(5_000L) { started.await() }
                        val callback = checkNotNull(fixture.appState.onPushTokenRotated("callback-token"))
                        release.complete(Unit)
                        assertFalse(ordinary.await())
                        callback.join()
                    }
                }
                assertEquals(listOf("callback-token"), fixture.upsertedPushTokens.toList())
                assertEquals("callback-token", PushTokenStore.create(context).lastToken())
                assertFalse(PushTokenStore.create(context).nativePushRegistrationSyncPending())
            } finally {
                release.complete(Unit)
                fixture.close()
                NotificationStreamForegroundService.stop(context)
            }
        }

    /** Uses the same owner invalidations in queued and suspended-query fixture cases. */
    private fun retirePushOwner(
        fixture: NotificationBootstrapTestFixture,
        retirement: String,
    ) {
        when (retirement) {
            "runtime" -> {
                val generation =
                    WhiteNoiseAppState::class.java
                        .getDeclaredField("runtimeGeneration\$delegate")
                        .apply { isAccessible = true }
                        .get(fixture.appState) as androidx.compose.runtime.MutableIntState
                generation.intValue += 1
            }
            "account" -> {
                @Suppress("UNCHECKED_CAST")
                val account =
                    WhiteNoiseAppState::class.java
                        .getDeclaredField("activeAccountRef\$delegate")
                        .apply { isAccessible = true }
                        .get(fixture.appState) as androidx.compose.runtime.MutableState<String?>
                account.value = "different-account"
            }
            "signout" -> fixture.appState.signOutInProgress = true
            "wipe" -> fixture.appState.wipeInProgress = true
            "clear" -> PushTokenStore.create(context).clear()
        }
    }

    private fun heldSdkPreferences(
        enteredWrite: kotlinx.coroutines.CompletableDeferred<Unit>,
        releaseWrite: java.util.concurrent.CountDownLatch,
    ): android.content.SharedPreferences {
        val preferences = context.getSharedPreferences("callback-atomic-test", android.content.Context.MODE_PRIVATE)
        return object : android.content.SharedPreferences by preferences {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    private var heldSdkWrite = false

                    override fun putString(
                        key: String?,
                        value: String?,
                    ): android.content.SharedPreferences.Editor {
                        heldSdkWrite = value == "older-sdk-token"
                        editor.putString(key, value)
                        return this
                    }

                    override fun apply() {
                        if (heldSdkWrite) {
                            enteredWrite.complete(Unit)
                            check(releaseWrite.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        }
                        editor.apply()
                    }
                }
            }
        }
    }

    private fun deliveryMutex(fixture: NotificationBootstrapTestFixture): kotlinx.coroutines.sync.Mutex =
        WhiteNoiseAppState::class.java
            .getDeclaredField("notificationDeliveryModeMutex")
            .apply { isAccessible = true }
            .get(fixture.appState) as kotlinx.coroutines.sync.Mutex

    /** Waits for the durable registration marker to clear. */
    private suspend fun awaitRegistrationSettled() {
        kotlinx.coroutines.withTimeout(5_000L) {
            while (PushTokenStore.create(context).nativePushRegistrationSyncPending()) kotlinx.coroutines.yield()
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
