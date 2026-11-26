package dev.ipf.whitenoise.android.state

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenKeyRule
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * A device-wide delivery-mode change can still take every account off native push, and turning Firebase
 * auto-init on starts an SDK token sync no later off write cancels (#4081). So while one is open, syncs and
 * per-account enables leave auto-init to the retry that change drains, and every change releases its hold.
 * Unreadable demand and an auto-init write without a stored retry stay unsettled instead of reporting success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FcmAutoInitDeliveryModeHandoffTest {
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

    /**
     * A sync for an account on Push that runs while a delivery-mode change is open registers it but leaves
     * auto-init alone with its retry pending, and ending that change drains the retry and turns auto-init on.
     */
    @Test
    fun syncDuringOpenDeliveryModeChangeLeavesAutoInitToItsEnd() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, activeNative = true)
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()
                val delivery = fixture.appState.notificationDelivery

                fixture.runWithMainLooperPumping {
                    delivery.beginDeliveryModeTransaction()
                    try {
                        assertTrue(fixture.appState.syncNativePushRegistrationIfEnabled())
                        assertTrue("an open mode change defers auto-init", platform.autoInitWrites.isEmpty())
                        val retry = store.nativePushRegistrationSyncPending()
                        assertTrue("the deferred sync must leave its retry pending", retry)
                    } finally {
                        delivery.endDeliveryModeTransaction()
                    }
                }

                assertEquals("the change's end drains the retry", true, platform.autoInitWrites.lastOrNull())
                assertFalse(store.nativePushRegistrationSyncPending())
            } finally {
                fixture.close()
            }
        }

    /**
     * With two delivery-mode changes open, a deferred retry waits for the last of them to end, so an older
     * handed-off change still running keeps auto-init off until it ends too.
     */
    @Test
    fun overlappingDeliveryModeChangesDrainOnlyWhenTheLastEnds() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, activeNative = true)
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()
                val delivery = fixture.appState.notificationDelivery

                fixture.runWithMainLooperPumping {
                    delivery.beginDeliveryModeTransaction()
                    delivery.beginDeliveryModeTransaction()
                    assertTrue(fixture.appState.syncNativePushRegistrationIfEnabled())
                    delivery.endDeliveryModeTransaction()
                    assertTrue("one change is still open", platform.autoInitWrites.isEmpty())
                    assertTrue(store.nativePushRegistrationSyncPending())
                    delivery.endDeliveryModeTransaction()
                }

                assertEquals("the last end drains the retry", true, platform.autoInitWrites.lastOrNull())
                assertFalse(store.nativePushRegistrationSyncPending())
                assertEquals(0, delivery.activeDeliveryModeTransactions.get())
            } finally {
                fixture.close()
            }
        }

    /**
     * A per-account Push enable that confirms while a delivery-mode change is open stores a retry instead of
     * turning auto-init on, since the change may still take the account off Push, and the change's end turns it on.
     */
    @Test
    fun perAccountEnableDuringOpenDeliveryModeChangeDefersAutoInit() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform)
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()
                val delivery = fixture.appState.notificationDelivery

                fixture.runWithMainLooperPumping {
                    delivery.beginDeliveryModeTransaction()
                    try {
                        assertTrue(fixture.appState.setNativePushEnabled(true))
                        val writes = platform.autoInitWrites.toList()
                        assertFalse("an open mode change defers auto-init: $writes", true in writes)
                        assertTrue("the enable must store its retry", store.nativePushRegistrationSyncPending())
                    } finally {
                        delivery.endDeliveryModeTransaction()
                    }
                }

                assertTrue("the enable must commit", fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertEquals("the change's end turns auto-init on", true, platform.autoInitWrites.lastOrNull())
            } finally {
                fixture.close()
            }
        }

    /**
     * A last end whose drain sync throws still ends the change without throwing, and keeps the durable retry for
     * the next sync, so a failed drain never replaces the change's own result.
     */
    @Test
    fun failedLastEndDrainKeepsItsRetryWithoutThrowing() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val failConfig = AtomicBoolean(false)
            val fixture =
                fixture(platform, activeNative = true) {
                    check(!failConfig.get()) { "push config unavailable" }
                    CONFIG
                }
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                val delivery = fixture.appState.notificationDelivery

                fixture.runWithMainLooperPumping {
                    delivery.beginDeliveryModeTransaction()
                    store.recordPendingNativePushRegistrationSync()
                    failConfig.set(true)
                    delivery.endDeliveryModeTransaction()
                }

                assertEquals(0, delivery.activeDeliveryModeTransactions.get())
                assertTrue("the failed drain keeps its retry", store.nativePushRegistrationSyncPending())
            } finally {
                failConfig.set(false)
                fixture.close()
            }
        }

    /**
     * A selection whose finish drain sync throws still ends its change, so later syncs do not keep deferring
     * auto-init to a change that already finished.
     */
    @Test
    fun throwingFinishDrainStillEndsTheChange() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val failConfig = AtomicBoolean(false)
            val fixture =
                fixture(platform, activeNative = true) {
                    check(!failConfig.get()) { "push config unavailable" }
                    CONFIG
                }
            try {
                fixture.bootstrap()
                val delivery = fixture.appState.notificationDelivery
                PushTokenStore.create(context).recordPendingNativePushRegistrationSync()
                failConfig.set(true)

                fixture.runWithMainLooperPumping {
                    runCatching { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                }

                assertEquals("the change must end", 0, delivery.activeDeliveryModeTransactions.get())
            } finally {
                failConfig.set(false)
                fixture.close()
            }
        }

    /** Push and Local selections, and a reconciliation that hands its cutover off, all release their hold. */
    @Test
    fun deliveryModeChangesReleaseTheirHold() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, activeNative = true)
            try {
                fixture.bootstrap()
                val delivery = fixture.appState.notificationDelivery

                fixture.runWithMainLooperPumping {
                    fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                }
                assertEquals(0, delivery.activeDeliveryModeTransactions.get())
                fixture.runWithMainLooperPumping {
                    fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local)
                }
                assertEquals(0, delivery.activeDeliveryModeTransactions.get())

                fixture.runWithMainLooperPumping {
                    fixture.replaceNotificationSettings(NotificationSettingsFfi(ACCOUNT_A, "$ACCOUNT_A-id", true, true))
                    delivery.reconcileNotificationDeliveryModeAfterActivation(includeBackgroundAccountModes = true)
                    withTimeout(10_000L) { while (delivery.notificationDeliveryModeBusy) yield() }
                }

                assertEquals(0, delivery.activeDeliveryModeTransactions.get())
            } finally {
                fixture.close()
            }
        }

    /**
     * A handed-off reconciliation whose scope is cancelled before its job starts still runs its closing block, so
     * it clears the busy flag and ends its change rather than leaving auto-init deferred until the next start.
     */
    @Test
    fun reconciliationCancelledBeforeItStartsStillEndsItsChange() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, activeNative = true)
            try {
                fixture.bootstrap()
                val delivery = fixture.appState.notificationDelivery

                fixture.runWithMainLooperPumping {
                    val generation = delivery.beginDeliveryModeTransaction()
                    val owner = checkNotNull(delivery.captureNotificationDeliveryModeOwner(generation))
                    delivery.notificationScope.cancel()
                    delivery.launchNotificationDeliveryModeReconciliation(
                        owner,
                        NotificationDeliveryMode.Local,
                        applyDeviceWideMode = true,
                    )
                    withTimeout(10_000L) {
                        while (delivery.notificationDeliveryModeBusy ||
                            delivery.activeDeliveryModeTransactions.get() != 0
                        ) {
                            yield()
                        }
                    }
                }

                assertFalse("the cancelled job must clear the busy flag", delivery.notificationDeliveryModeBusy)
                assertEquals("the cancelled job must end its change", 0, delivery.activeDeliveryModeTransactions.get())
            } finally {
                fixture.close()
            }
        }

    /**
     * An enable checks that no delivery-mode change is open, then reads account demand. A Local choice that opens
     * during that read is seen again with the SDK write, so the enable writes nothing and stores its retry instead
     * of starting Firebase work the Local cutover's off write cannot cancel.
     */
    @Test
    fun deliveryModeChangeOpenedDuringTheDemandReadFencesTheOnWrite() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val blockReads = AtomicBoolean(false)
            val reading = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fixture =
                fixture(platform, activeNative = true, onNotificationSettings = {
                    if (blockReads.get()) {
                        reading.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                    null
                })
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()
                val delivery = fixture.appState.notificationDelivery

                fixture.runWithMainLooperPumping {
                    blockReads.set(true)
                    val enable =
                        async(Dispatchers.Default) {
                            delivery.commitFcmAutoInitAfterEnable { delivery.activeDeliveryModeTransactions.get() == 0 }
                        }
                    assertTrue("the demand read must start", reading.await(10, TimeUnit.SECONDS))
                    delivery.beginDeliveryModeTransaction()
                    blockReads.set(false)
                    release.countDown()
                    assertTrue("the superseded enable stores its retry", enable.await())
                }

                val writes = platform.autoInitWrites.toList()
                assertFalse("a superseded enable must not turn auto-init on: $writes", true in writes)
                assertTrue("the retry stays for the change to drain", store.nativePushRegistrationSyncPending())
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /**
     * The last change's deferred retry is cancellable and bounded: through finish and then end, as every change
     * closes, a Firebase token task that never completes cannot keep the mode or native sync lock from sign-out or
     * wipe, and the retry keeps its durable marker.
     */
    @Test
    fun stalledDeferredRetryReleasesTheSyncLockAndKeepsItsRetry() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val stalled = AtomicBoolean(false)
            val fixture =
                fixture(platform, activeNative = true, fcmTokenReader = {
                    if (stalled.get()) awaitCancellation()
                    TOKEN
                })
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                val delivery = fixture.appState.notificationDelivery
                delivery.deferredRegistrationDrainTimeoutMs = 300L

                fixture.runWithMainLooperPumping {
                    val generation = delivery.beginDeliveryModeTransaction()
                    store.recordPendingNativePushRegistrationSync()
                    stalled.set(true)
                    // The real lifecycle, as applyMode closes a change: finish, then end. Its own scope, so a retry
                    // that ignores cancellation fails this check instead of hanging the test.
                    val ending =
                        CoroutineScope(Dispatchers.Default).launch {
                            try {
                                delivery.finishNotificationDeliveryModeTransaction(generation)
                            } finally {
                                delivery.endDeliveryModeTransaction()
                            }
                        }
                    val ended = withTimeoutOrNull(5_000L) { ending.join() }
                    assertTrue("the change must end within the retry's bound", ended != null)
                }

                assertFalse("the stalled retry must release the sync lock", delivery.nativePushSyncMutex.isLocked)
                assertFalse("finish must release the mode lock", delivery.notificationDeliveryModeMutex.isLocked)
                assertFalse("finish clears the busy flag", delivery.notificationDeliveryModeBusy)
                assertTrue("the stalled retry keeps its marker", store.nativePushRegistrationSyncPending())
                assertEquals(0, delivery.activeDeliveryModeTransactions.get())
            } finally {
                stalled.set(false)
                fixture.close()
            }
        }

    /** A committed enable whose on write fails and whose retry cannot be stored reports auto-init unsettled. */
    @Test
    fun committedEnableWithFailedWriteAndUnstoredRetryReportsUnsettled() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, activeNative = true)
            try {
                fixture.bootstrap()
                fixture.replacePushTokenStore(markerRejectingStore())
                platform.failAutoInitWrites = true

                val settled =
                    fixture.runWithMainLooperPumping {
                        fixture.appState.notificationDelivery.commitFcmAutoInitAfterEnable { true }
                    }

                assertFalse("a commit without its write or retry must not report success", settled)
            } finally {
                platform.failAutoInitWrites = false
                fixture.close()
            }
        }

    /**
     * A Local selection whose final off write fails and whose retry cannot be stored reports failure, while
     * Local delivery stays established and every account stays off Push.
     */
    @Test
    fun localSelectionWithFailedOffWriteAndUnstoredRetryReportsFailure() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, activeNative = true)
            try {
                fixture.bootstrap()
                fixture.replacePushTokenStore(markerRejectingStore())
                platform.failAutoInitWrites = true

                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                fixture.runWithMainLooperPumping { withTimeout(10_000L) { platform.awaitFirstStart() } }
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("Local service acknowledgement was not accepted")

                val selected = fixture.runWithMainLooperPumping { result.await().also { acknowledgement.join() } }
                assertFalse("a cutover with auto-init unsettled and no retry must not report success", selected)
                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue("Local delivery stays established", BackgroundConnectionPreferences.isEnabled(context))
            } finally {
                platform.failAutoInitWrites = false
                fixture.close()
            }
        }

    /** A sync whose on write fails and whose retry cannot be stored still registers but reports failure. */
    @Test
    fun syncWithFailedWriteAndUnstoredRetryReportsFailure() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, activeNative = true)
            try {
                fixture.bootstrap()
                fixture.replacePushTokenStore(markerRejectingStore())
                fixture.upsertedPushRegistrations.clear()
                platform.failAutoInitWrites = true

                val synced =
                    fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() }

                assertFalse("a sync with auto-init unsettled and no retry must not report success", synced)
                assertTrue("it still registers the account", ACCOUNT_A in fixture.upsertedPushRegistrations)
            } finally {
                platform.failAutoInitWrites = false
                fixture.close()
            }
        }

    /**
     * A sync without the native-push capability whose demand cannot be read afterwards leaves the durable
     * registration sync pending, since its fallback may have cleared the marker for an account still on Push.
     */
    @Test
    fun unavailableCapabilitySyncWithUnreadableDemandRecordsPendingSync() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val failReads = AtomicBoolean(false)
            val fixture =
                fixture(
                    platform,
                    capability = NativePushCapability.GooglePlayServicesUnavailable,
                    onNotificationSettings = { if (failReads.get()) error("settings unavailable") else null },
                )
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()
                failReads.set(true)

                fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() }

                assertTrue("unreadable demand must leave its retry pending", store.nativePushRegistrationSyncPending())
                assertTrue("unreadable demand never writes auto-init", platform.autoInitWrites.isEmpty())
            } finally {
                failReads.set(false)
                fixture.close()
            }
        }

    /**
     * A sign-out's native disable whose remaining demand cannot be read leaves the durable registration sync
     * pending, so the next sync settles auto-init for any account still on Push.
     */
    @Test
    fun signOutNativeDisableWithUnreadableDemandRecordsPendingSync() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val failReads = AtomicBoolean(false)
            val fixture =
                fixture(
                    platform,
                    onNotificationSettings = { ref ->
                        if (failReads.get() && ref == ACCOUNT_B) error("settings unavailable") else null
                    },
                )
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()
                failReads.set(true)

                fixture.runWithMainLooperPumping {
                    fixture.appState.notificationDelivery.settleFcmAutoInitAfterSignOutNativeDisable(ACCOUNT_A)
                }

                assertTrue("unreadable demand must leave its retry pending", store.nativePushRegistrationSyncPending())
                assertTrue("unreadable demand never writes auto-init", platform.autoInitWrites.isEmpty())
            } finally {
                failReads.set(false)
                fixture.close()
            }
        }

    /** A push store whose pending-registration-sync marker never commits, while every other write does. */
    private fun markerRejectingStore(): PushTokenStore {
        val preferences = context.getSharedPreferences("handoff-marker-rejected", Context.MODE_PRIVATE)
        return PushTokenStore(
            object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    var markerWritten = false
                    return object : SharedPreferences.Editor by editor {
                        override fun putBoolean(
                            key: String?,
                            value: Boolean,
                        ): SharedPreferences.Editor {
                            if (key == KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC) markerWritten = true
                            editor.putBoolean(key, value)
                            return this
                        }

                        override fun commit(): Boolean = !markerWritten && editor.commit()
                    }
                }
            },
        )
    }

    /** Builds two local-signing accounts behind [platform], the active one on native push when [activeNative]. */
    private fun fixture(
        platform: RecordingNativePushFallbackPlatform,
        activeNative: Boolean = false,
        capability: NativePushCapability = NativePushCapability.Available,
        onNotificationSettings: ((String) -> NotificationSettingsFfi?)? = null,
        fcmTokenReader: suspend () -> String? = { TOKEN },
        pushServerConfig: () -> PushServerConfig? = { CONFIG },
    ) = NotificationBootstrapTestFixture(
        context = context,
        accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
        initialNotificationSettings = NotificationSettingsFfi(ACCOUNT_A, "$ACCOUNT_A-id", true, activeNative),
        nativePushFallbackPlatform = platform,
        onNotificationSettings = onNotificationSettings,
        pushServerConfigProvider = pushServerConfig,
        fcmTokenReader = fcmTokenReader,
        nativePushCapabilityResolver = { capability },
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
