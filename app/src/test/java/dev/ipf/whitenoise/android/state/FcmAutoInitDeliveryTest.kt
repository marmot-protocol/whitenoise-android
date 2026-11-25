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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/**
 * Firebase auto-init is off in the manifest (#4081). The delivery controller turns it on right before
 * it reads an FCM token for native push and again once registration completes, and off when every
 * account leaves native push, a selection fails or the last account is removed. A device that only
 * uses the persistent connection never turns it on and never reads a token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FcmAutoInitDeliveryTest {
    @get:Rule
    val pushTokenKey = PushTokenKeyRule()

    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    /** Starts every test with the persistent connection off and the notification permission granted. */
    @Before
    fun resetDeliveryState() {
        BackgroundConnectionPreferences.setEnabledDurably(context, false)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Stops the foreground service a persistent-delivery test may have started. */
    @After
    fun stopTestService() {
        NotificationStreamForegroundService.stop(context)
    }

    /** Selecting native push turns auto-init on before the token read and leaves it on. */
    @Test
    fun fcmSelectionEnablesAutoInitBeforeTokenRead() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val writesAtTokenRead = CopyOnWriteArrayList<List<Boolean>>()
            val fixture =
                fixture(platform, nativeEnabled = false) {
                    writesAtTokenRead += platform.autoInitWrites.toList()
                    TOKEN
                }
            try {
                fixture.bootstrap()

                assertTrue(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                    },
                )

                assertTrue("the selection must read a token", writesAtTokenRead.isNotEmpty())
                writesAtTokenRead.forEach { writes -> assertEquals(true, writes.lastOrNull()) }
                assertEquals(true, platform.autoInitWrites.last())
                assertEquals(setOf(ACCOUNT_A, ACCOUNT_B), fixture.upsertedPushRegistrations.toSet())
            } finally {
                fixture.close()
            }
        }

    /** The SDK's first-token callback, which arrives before getToken() resolves, does not fail the selection. */
    @Test
    fun firstTokenCallbackDuringSelectionKeepsSelection() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            lateinit var fixture: NotificationBootstrapTestFixture
            fixture =
                fixture(platform, nativeEnabled = false) {
                    // Firebase saves a new token and calls onNewToken before completing the getToken() task.
                    fixture.appState.onPushTokenRotated(TOKEN)
                    TOKEN
                }
            try {
                fixture.bootstrap()

                assertTrue(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                    },
                )

                assertEquals(setOf(ACCOUNT_A, ACCOUNT_B), fixture.upsertedPushRegistrations.toSet())
                assertEquals(true, platform.autoInitWrites.last())
            } finally {
                fixture.close()
            }
        }

    /** A Push selection whose token read fails turns auto-init back off, since no account uses Push. */
    @Test
    fun failedFcmSelectionTurnsAutoInitBackOff() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, nativeEnabled = false) { null }
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()

                assertFalse(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                    },
                )

                // A follow-up sync may repeat the `false`, but nothing may turn it back on.
                assertEquals(listOf(true, false), platform.autoInitWrites.distinct())
                assertEquals(false, platform.autoInitWrites.last())
                assertTrue(fixture.upsertedPushRegistrations.isEmpty())
            } finally {
                fixture.close()
            }
        }

    /** A first Push selection whose registration fails rolls back and turns auto-init back off. */
    @Test
    fun failedRegistrationTurnsAutoInitBackOff() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(platform, nativeEnabled = false, onUpsert = { error("registration failed") }) { TOKEN }
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()

                assertFalse(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                    },
                )

                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
                assertEquals(true, platform.autoInitWrites.first())
                assertEquals(false, platform.autoInitWrites.last())
            } finally {
                fixture.close()
            }
        }

    /** A failed Push reselection for accounts that already use Push never turns auto-init off. */
    @Test
    fun failedReselectionKeepsAutoInitForExistingPushAccounts() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            var failUpserts = false
            var token = TOKEN
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = true,
                    onUpsert = { if (failUpserts) error("registration failed") },
                ) { token }
            try {
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                fixture.bootstrap()
                platform.autoInitWrites.clear()
                // A rotated token forces a fresh registration, which now fails.
                token = "rotated-token"
                failUpserts = true

                assertFalse(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                    },
                )

                assertTrue(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertTrue(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
                assertFalse("existing Push accounts must keep auto-init", false in platform.autoInitWrites)
            } finally {
                failUpserts = false
                fixture.close()
            }
        }

    /** A stale `false` write that lands after the selection's token read cannot leave Push without auto-init. */
    @Test
    fun lateDisableDuringFcmSelectionIsOverwrittenAfterRegistration() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture =
                fixture(platform, nativeEnabled = false) {
                    // Models a concurrent no-Push sync whose write finishes after the selection's `true`.
                    platform.setFcmAutoInitEnabled(false)
                    TOKEN
                }
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()

                assertTrue(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Fcm)
                    },
                )

                assertEquals(listOf(true, false, true), platform.autoInitWrites.take(3))
                assertEquals(true, platform.autoInitWrites.last())
            } finally {
                fixture.close()
            }
        }

    /** Removing the last signed-in account drops the cached token and turns auto-init off. */
    @Test
    fun lastAccountRemovalTurnsAutoInitOff() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val fixture = fixture(platform, nativeEnabled = true) { TOKEN }
            try {
                fixture.bootstrap()
                val store = PushTokenStore.create(context)
                store.setToken(TOKEN)
                platform.autoInitWrites.clear()

                fixture.appState.notificationDelivery.forgetFcmTokenAfterLastAccount()

                assertEquals(listOf(false), platform.autoInitWrites)
                assertNull(store.lastToken())
            } finally {
                fixture.close()
            }
        }

    /** An account that already uses native push turns auto-init on during its next sync, before the read. */
    @Test
    fun nativePushSyncEnablesAutoInitBeforeTokenRead() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val writesAtTokenRead = CopyOnWriteArrayList<List<Boolean>>()
            val fixture =
                fixture(platform, nativeEnabled = true) {
                    writesAtTokenRead += platform.autoInitWrites.toList()
                    TOKEN
                }
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                platform.autoInitWrites.clear()
                writesAtTokenRead.clear()

                assertTrue(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })

                assertEquals(listOf(listOf(true)), writesAtTokenRead)
                assertEquals(listOf(true), platform.autoInitWrites)
                assertTrue(fixture.upsertedPushRegistrations.containsAll(listOf(ACCOUNT_A, ACCOUNT_B)))
            } finally {
                fixture.close()
            }
        }

    /** Leaving native push for every signed-in account turns auto-init off. */
    @Test
    fun localSelectionDisablesAutoInitForEveryAccount() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val tokenReads = CopyOnWriteArrayList<Unit>()
            val fixture =
                fixture(platform, nativeEnabled = true) {
                    tokenReads += Unit
                    TOKEN
                }
            try {
                fixture.bootstrap()
                fixture.replaceNotificationSettings(settings(ACCOUNT_B, nativeEnabled = true))
                platform.autoInitWrites.clear()
                tokenReads.clear()
                val result = async { fixture.appState.setNotificationDeliveryMode(NotificationDeliveryMode.Local) }
                fixture.runWithMainLooperPumping { withTimeout(10_000L) { platform.awaitFirstStart() } }
                val acknowledgement =
                    fixture.beginNativePushFallbackRuntimeAcknowledgement(platform.starts.last())
                        ?: error("Local service acknowledgement was not accepted")

                assertTrue(fixture.runWithMainLooperPumping { result.await().also { acknowledgement.join() } })

                assertFalse(fixture.notificationSettings(ACCOUNT_A).nativePushEnabled)
                assertFalse(fixture.notificationSettings(ACCOUNT_B).nativePushEnabled)
                assertFalse("leaving native push must never enable auto-init", true in platform.autoInitWrites)
                assertEquals(false, platform.autoInitWrites.lastOrNull())
                assertTrue("leaving native push must not read a token", tokenReads.isEmpty())
            } finally {
                fixture.close()
            }
        }

    /** A device on the persistent connection syncs without enabling auto-init or reading a token. */
    @Test
    fun persistentOnlySyncNeverEnablesAutoInitOrReadsToken() =
        runBlocking {
            BackgroundConnectionPreferences.setEnabledDurably(context, true)
            val platform = RecordingNativePushFallbackPlatform(context)
            val tokenReads = CopyOnWriteArrayList<Unit>()
            val fixture =
                fixture(platform, nativeEnabled = false) {
                    tokenReads += Unit
                    TOKEN
                }
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()

                assertTrue(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })

                val writes = platform.autoInitWrites.distinct()
                assertEquals("a sync with no Push account turns auto-init off", listOf(false), writes)
                assertTrue("persistent delivery must not read a token", tokenReads.isEmpty())
                assertTrue(fixture.upsertedPushRegistrations.isEmpty())
            } finally {
                fixture.close()
            }
        }

    /** A sync that finds native push unavailable turns auto-init off and reads no token, even for a Push account. */
    @Test
    fun unavailableCapabilitySyncTurnsAutoInitOff() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val tokenReads = CopyOnWriteArrayList<Unit>()
            val unavailable = NativePushCapability.GooglePlayServicesUnavailable
            val fixture =
                fixture(platform, nativeEnabled = true, capability = unavailable) {
                    tokenReads += Unit
                    TOKEN
                }
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()

                fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() }

                assertEquals(false, platform.autoInitWrites.firstOrNull())
                assertFalse(true in platform.autoInitWrites)
                assertTrue(tokenReads.isEmpty())
            } finally {
                fixture.close()
            }
        }

    /** A settings read failure with no account known to need push neither reads a token nor flips auto-init. */
    @Test
    fun unreadableSettingsKeepAutoInitUntouched() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val tokenReads = CopyOnWriteArrayList<Unit>()
            var failReads = false
            val fixture =
                fixture(
                    platform = platform,
                    nativeEnabled = false,
                    onNotificationSettings = { if (failReads) error("settings unavailable") else null },
                ) {
                    tokenReads += Unit
                    TOKEN
                }
            try {
                fixture.bootstrap()
                platform.autoInitWrites.clear()
                failReads = true

                assertFalse(fixture.runWithMainLooperPumping { fixture.appState.syncNativePushRegistrationIfEnabled() })

                assertTrue(platform.autoInitWrites.isEmpty())
                assertTrue(tokenReads.isEmpty())
            } finally {
                failReads = false
                fixture.close()
            }
        }

    /** Builds a two-account fixture with the given push capability and token reader. */
    private fun fixture(
        platform: RecordingNativePushFallbackPlatform,
        nativeEnabled: Boolean,
        onNotificationSettings: ((String) -> NotificationSettingsFfi?)? = null,
        capability: NativePushCapability = NativePushCapability.Available,
        onUpsert: (String) -> Unit = {},
        fcmTokenReader: suspend () -> String?,
    ) = NotificationBootstrapTestFixture(
        context = context,
        accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
        initialNotificationSettings = settings(ACCOUNT_A, nativeEnabled),
        nativePushFallbackPlatform = platform,
        onUpsertPushRegistration = onUpsert,
        onNotificationSettings = onNotificationSettings,
        pushServerConfigProvider = { CONFIG },
        fcmTokenReader = fcmTokenReader,
        nativePushCapabilityResolver = { capability },
    )

    /** Builds a running local-signing account summary for the given ref. */
    private fun account(ref: String) = AccountSummaryFfi(ref, "$ref-id", true, false, false, true, false)

    /** Builds notification settings with rendering on and the given native-push flag. */
    private fun settings(
        ref: String,
        nativeEnabled: Boolean,
    ) = NotificationSettingsFfi(ref, "$ref-id", true, nativeEnabled)

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val TOKEN = "test-token"
        val CONFIG = PushServerConfig("0".repeat(64), null)
    }
}
