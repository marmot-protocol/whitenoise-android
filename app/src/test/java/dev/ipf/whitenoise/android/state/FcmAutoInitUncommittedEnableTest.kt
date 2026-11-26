package dev.ipf.whitenoise.android.state

import android.Manifest
import android.content.Context
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Turning Firebase auto-init on starts the SDK's background token sync, and no later off write cancels it
 * (#4081). So a native-push enable that can still roll back never turns it on: a Push selection waits for its
 * whole cutover to commit, a per-account enable waits for its confirmation, and a sync that runs meanwhile
 * leaves it alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FcmAutoInitUncommittedEnableTest {
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

    /** Stops the foreground service a rolled-back cutover may have restarted. */
    @After
    fun stopTestService() {
        NotificationStreamForegroundService.stop(context)
    }

    /** A Push cutover whose Local preference cannot be saved after registration never turns auto-init on. */
    @Test
    fun cutoverPreferenceFailureAfterRegistrationNeverEnablesAutoInit() =
        runBlocking {
            val persistResults = ArrayDeque<Boolean>()
            val platform = RecordingNativePushFallbackPlatform(context, persistResults = persistResults)
            val fixture = fixture(platform) { TOKEN }
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()
                persistResults.addLast(false)

                assertFalse(fixture.selectPush())

                assertEquals(setOf(ACCOUNT_A, ACCOUNT_B), fixture.upsertedPushRegistrations.toSet())
                assertTrue("the cutover must have reached the preference write", persistResults.isEmpty())
                val writes = platform.autoInitWrites.toList()
                assertFalse("a cutover that fails must never turn auto-init on: $writes", true in writes)
            } finally {
                fixture.close()
            }
        }

    /**
     * A Push cutover whose Local service cannot be stopped after registration restarts that service, rolls the
     * accounts back off native push once the restart is acknowledged, and never turns auto-init on.
     */
    @Test
    fun cutoverServiceStopFailureAfterRegistrationNeverEnablesAutoInit() =
        runBlocking {
            val stopResults = ArrayDeque<Boolean>()
            val platform = RecordingNativePushFallbackPlatform(context, stopResults = stopResults)
            val fixture = fixture(platform) { TOKEN }
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()
                stopResults.addLast(false)
                val startsBefore = platform.starts.size

                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }
                fixture.runWithMainLooperPumping {
                    withTimeout(10_000L) { while (platform.starts.size == startsBefore) yield() }
                }
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("the Local service restart was not accepted")
                assertFalse(fixture.runWithMainLooperPumping { result.await().also { acknowledgement.join() } })

                assertEquals(setOf(ACCOUNT_A, ACCOUNT_B), fixture.upsertedPushRegistrations.toSet())
                assertTrue("the cutover must have reached the service stop", stopResults.isEmpty())
                assertFalse("the rollback takes Push off", fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                val writes = platform.autoInitWrites.toList()
                assertFalse("a cutover that fails must never turn auto-init on: $writes", true in writes)
            } finally {
                fixture.close()
            }
        }

    /**
     * A sync that runs after the selection's registrations but before its cutover commits sees the accounts on
     * native push, yet leaves auto-init alone, so the cutover's later failure leaves it off.
     */
    @Test
    fun syncDuringUncommittedCutoverLeavesAutoInitAlone() =
        runBlocking {
            val armed = AtomicBoolean(false)
            val fixtureRef = AtomicReference<NotificationBootstrapTestFixture>()
            val syncRan = AtomicBoolean(false)
            val tokenReads = AtomicInteger()
            val persistResults = ArrayDeque<Boolean>()
            val platform =
                RecordingNativePushFallbackPlatform(
                    context,
                    persistResults = persistResults,
                    beforePersist = {
                        if (armed.getAndSet(false)) {
                            runBlocking { fixtureRef.get().appState.syncNativePushRegistrationIfEnabled() }
                            syncRan.set(true)
                        }
                    },
                )
            val fixture =
                fixture(platform) {
                    tokenReads.incrementAndGet()
                    TOKEN
                }
            fixtureRef.set(fixture)
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()
                tokenReads.set(0)
                persistResults.addLast(false)
                armed.set(true)

                assertFalse(fixture.selectPush())

                assertTrue("a sync must run inside the cutover", syncRan.get())
                assertEquals("the sync must reach its token read", 2, tokenReads.get())
                val writes = platform.autoInitWrites.toList()
                assertFalse("no sync may turn auto-init on before the cutover commits: $writes", true in writes)
            } finally {
                armed.set(false)
                fixture.close()
            }
        }

    /** A per-account Push enable turns auto-init on only once its registration has confirmed it. */
    @Test
    fun perAccountEnableTurnsAutoInitOnOnlyOnceConfirmed() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val writesAtUpsert = CopyOnWriteArrayList<List<Boolean>>()
            val fixture = fixture(platform, onUpsert = { writesAtUpsert += platform.autoInitWrites.toList() }) { TOKEN }
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()

                assertTrue(fixture.runWithMainLooperPumping { fixture.appState.setNativePushEnabled(true) })

                assertTrue("the enable must register", writesAtUpsert.isNotEmpty())
                writesAtUpsert.forEach { writes -> assertFalse("on before confirmation: $writes", true in writes) }
                assertEquals(true, platform.autoInitWrites.lastOrNull())
            } finally {
                fixture.close()
            }
        }

    /**
     * A committed enable that a newer change has superseded before its auto-init write leaves the durable
     * registration sync pending instead of writing nothing, so the next sync settles auto-init.
     */
    @Test
    fun supersededCommitRecordsPendingSync() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform) { TOKEN }
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.clearPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()

                fixture.runWithMainLooperPumping {
                    fixture.appState.notificationDelivery.commitFcmAutoInitAfterEnable { false }
                }

                assertTrue("the next sync must settle auto-init", store.nativePushRegistrationSyncPending())
                assertTrue("a superseded commit writes nothing", platform.autoInitWrites.isEmpty())
            } finally {
                fixture.close()
            }
        }

    /**
     * A sync for an account already on Push that runs while another enable is still uncommitted leaves auto-init
     * alone and keeps the durable registration sync pending, so a later sync turns auto-init on even if that
     * enable fails without touching it.
     */
    @Test
    fun syncDuringOpenEnableKeepsPendingSync() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, false)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, activeNative = true) { TOKEN }
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.recordPendingNativePushRegistrationSync()
                platform.autoInitWrites.clear()
                val delivery = fixture.appState.notificationDelivery

                delivery.uncommittedNativePushEnables.incrementAndGet()
                try {
                    val appState = fixture.appState
                    val synced = fixture.runWithMainLooperPumping { appState.syncNativePushRegistrationIfEnabled() }
                    assertTrue("the deferred sync still registers", synced)
                } finally {
                    delivery.uncommittedNativePushEnables.decrementAndGet()
                }

                assertTrue("an open enable defers auto-init", platform.autoInitWrites.isEmpty())
                assertTrue("the marker must survive the deferred sync", store.nativePushRegistrationSyncPending())

                assertTrue(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })

                assertEquals(true, platform.autoInitWrites.lastOrNull())
                assertFalse(store.nativePushRegistrationSyncPending())
            } finally {
                fixture.close()
            }
        }

    /** Builds two local-signing accounts behind [platform], the active one on native push when [activeNative]. */
    private fun fixture(
        platform: RecordingNativePushFallbackPlatform,
        activeNative: Boolean = false,
        onUpsert: (String) -> Unit = {},
        fcmTokenReader: suspend () -> String?,
    ) = NotificationBootstrapTestFixture(
        context = context,
        accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
        initialNotificationSettings = NotificationSettingsFfi(ACCOUNT_A, "$ACCOUNT_A-id", true, activeNative),
        nativePushFallbackPlatform = platform,
        onUpsertPushRegistration = onUpsert,
        pushServerConfigProvider = { CONFIG },
        fcmTokenReader = fcmTokenReader,
        nativePushCapabilityResolver = { NativePushCapability.Available },
    )

    /** Selects Push delivery explicitly, as the settings screen does. */
    private suspend fun NotificationBootstrapTestFixture.selectPush(): Boolean =
        runWithMainLooperPumping { appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm) }

    /** Builds a running local-signing account summary for the given ref. */
    private fun account(ref: String) = AccountSummaryFfi(ref, "$ref-id", true, false, false, true, false)

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val TOKEN = "test-token"
        val CONFIG = PushServerConfig("0".repeat(64), null)
    }
}
