package dev.ipf.whitenoise.android.notifications

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrigger
import dev.ipf.whitenoise.android.state.KeystoreSecureStore
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Verifies that best-effort durable bookkeeping failures never mask recovery outcomes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushWakeBookkeepingFailureTest {
    @get:Rule
    val pushTokenKey = PushTokenKeyRule()

    private var previousKeepConnected = false

    /** Uses one-shot delivery so each test exercises the push-wake owner. */
    @Before
    fun useOneShotPushDelivery() {
        val context: Application = RuntimeEnvironment.getApplication()
        previousKeepConnected = BackgroundConnectionPreferences.isEnabled(context)
        BackgroundConnectionPreferences.setEnabled(context, false)
    }

    /** Restores the process-global delivery preference. */
    @After
    fun restoreDeliveryMode() {
        val context: Application = RuntimeEnvironment.getApplication()
        BackgroundConnectionPreferences.setEnabled(context, previousKeepConnected)
    }

    /** Locked encrypted storage defers startup draining and preserves the exact wake for later recovery. */
    @Test
    fun unavailableKeyDefersStartupWithoutLosingThePendingWake() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val unavailable = AtomicBoolean(false)
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            PushTokenStore.installKeyProvider(
                object : SecureStoreKeyProvider {
                    override fun secretKey(): SecretKey {
                        if (unavailable.get()) throw GeneralSecurityException("scripted unavailable key")
                        return key
                    }
                },
            )
            val store = PushTokenStore.create(context)
            val nativeCatchUps = AtomicInteger(0)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    emitStartupNotification = false,
                    onCatchUpAccounts = { nativeCatchUps.incrementAndGet() },
                )
            try {
                fixture.bootstrap()
                fixture.replacePushTokenStore(store)
                assertTrue(store.recordPendingPushWakeCatchUp())
                val generation = store.pendingPushWakeCatchUpGeneration()
                val phase = fixture.appState.phase
                unavailable.set(true)

                val drained =
                    fixture.runWithMainLooperPumping {
                        fixture.appState.ensureNotificationRuntimeStarted()
                    }

                assertFalse(drained)
                fixture.appState.javaClass
                    .getDeclaredMethod("schedulePendingPushWakeCatchUpDrain")
                    .apply { isAccessible = true }
                    .invoke(fixture.appState)
                assertEquals(phase, fixture.appState.phase)
                val callsBeforeRejectedWake = nativeCatchUps.get()
                val rejectedWake =
                    fixture.runWithMainLooperPumping {
                        launchRejectedPushWake(fixture)
                    }
                assertFalse(rejectedWake.succeeded)
                assertEquals(callsBeforeRejectedWake, nativeCatchUps.get())
                assertTrue(
                    fixture.runWithMainLooperPumping { fixture.appState.launchCatchUpAccounts().await() }.succeeded,
                )
                assertFalse(store.registrationAllowed())
                unavailable.set(false)
                assertEquals(generation, store.pendingPushWakeCatchUpGeneration())
                assertTrue(
                    fixture.runWithMainLooperPumping {
                        fixture.appState.ensureNotificationRuntimeStarted()
                    },
                )
                assertFalse(store.pushWakeCatchUpPending())
            } finally {
                unavailable.set(false)
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Invokes the production push-wake admission path without another lifecycle trigger. */
    private suspend fun launchRejectedPushWake(
        fixture: NotificationBootstrapTestFixture,
    ): dev.ipf.whitenoise.android.state.AccountCatchUpResult {
        val launch =
            fixture.appState.javaClass
                .getDeclaredMethod(
                    "launchAccountCatchUp",
                    java.lang.Long::class.java,
                    PerformanceTrigger::class.java,
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val work =
            launch.invoke(fixture.appState, null, PerformanceTrigger.PUSH_WAKE, true) as
                kotlinx.coroutines.Deferred<dev.ipf.whitenoise.android.state.AccountCatchUpResult>
        return work.await()
    }

    /** A token commit followed by Keystore loss cannot escape the launched marker-write coroutine. */
    @Test
    fun tokenRotationSurvivesKeyLossBeforePendingSyncCommit() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val unavailable = AtomicBoolean(false)
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val preferences =
                PushTokenPreferences(
                    KeystoreSecureStore(
                        context,
                        "rotation-key-race",
                        object : SecureStoreKeyProvider {
                            override fun secretKey(): SecretKey {
                                if (unavailable.get()) throw GeneralSecurityException("scripted key loss")
                                return key
                            }
                        },
                    ),
                    Any(),
                )
            val store =
                PushTokenStore(
                    object : SharedPreferences by preferences {
                        override fun edit(): SharedPreferences.Editor {
                            val editor = preferences.edit()
                            return object : SharedPreferences.Editor by editor {
                                override fun putString(
                                    name: String,
                                    value: String?,
                                ): SharedPreferences.Editor = apply { editor.putString(name, value) }

                                override fun apply() {
                                    editor.apply()
                                    unavailable.set(true)
                                }
                            }
                        }
                    },
                )
            val fixture = NotificationBootstrapTestFixture(context, emitStartupNotification = false)
            try {
                fixture.bootstrap()
                fixture.replacePushTokenStore(store)
                ShadowLog.clear()
                fixture.appState.onPushTokenRotated("rotated-token")
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) {
                        while (
                            ShadowLog.getLogsForTag("PushTokenStorage").none { it.msg == "lifecycle_work_deferred" }
                        ) {
                            delay(5L)
                        }
                    }
                }
                assertTrue(fixture.upsertedPushTokens.isEmpty())
                unavailable.set(false)
                assertEquals("rotated-token", store.lastToken())
                assertFalse(store.nativePushRegistrationSyncPending())
            } finally {
                unavailable.set(false)
                fixture.close()
            }
        }

    /** Concurrent acknowledgement cannot replace runtime failure with a bookkeeping assertion. */
    @Test
    fun clearedWakeDuringRuntimeFailurePreservesRecoveryFailure() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    emitStartupNotification = false,
                    onCatchUpAccounts = {
                        store.clearPendingPushWakeCatchUp()
                        throw IOException("synthetic runtime failure")
                    },
                )
            try {
                store.recordPendingPushWakeCatchUp()

                val failure =
                    runCatching {
                        fixture.runWithMainLooperPumping { fixture.appState.runPushWakeRecoveryAttempt() }
                    }.exceptionOrNull()

                assertEquals("push wake account catch-up incomplete", failure?.message)
                assertFalse(store.pushWakeCatchUpPending())
            } finally {
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** A failed completion commit leaves the durable wake pending and reports persistence failure. */
    @Test
    fun failedCompletionCommitDoesNotEscapeRecovery() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val preferences = context.getSharedPreferences("push-wake-failed-completion", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val baseStore = PushTokenStore(preferences)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    emitStartupNotification = false,
                    onCatchUpAccounts = {},
                )
            try {
                fixture.bootstrap()
                baseStore.recordPendingPushWakeCatchUp()
                fixture.replacePushTokenStore(PushTokenStore(FailingCompletionCommitPreferences(preferences)))
                startDiagnostics()

                val recovery = runCatching { fixture.awaitPushDrain(100L) }

                assertEquals("push wake account catch-up incomplete", recovery.exceptionOrNull()?.message)
                assertTrue(baseStore.pushWakeCatchUpPending())
                assertEquals(1, baseStore.pushWakeAttempts())
                assertPersistenceFailureLogged()
            } finally {
                stopDiagnostics()
                baseStore.clearPendingPushWakeCatchUp()
                fixture.close()
                preferences.edit().clear().commit()
            }
        }

    /** A failed admission commit remains rejected while emitting its privacy-safe failure phase. */
    @Test
    fun failedAdmissionCommitIsRejectedAndDiagnosed() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val preferences = context.getSharedPreferences("push-wake-failed-admission", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val baseStore = PushTokenStore(preferences)
            try {
                assertTrue(baseStore.recordPendingPushWakeCatchUp())
                startDiagnostics()
                val budget =
                    PushWakeAttemptBudget(
                        store = PushTokenStore(FailingAdmissionCommitPreferences(preferences)),
                        storageDispatcher = Dispatchers.Unconfined,
                        nowMs = { 1_000L },
                    )

                assertEquals(PushWakeAdmission.Rejected, budget.reserve())
                assertTrue(baseStore.pushWakeCatchUpPending())
                assertEquals(0, baseStore.pushWakeAttempts())
                assertPersistenceFailureLogged()
            } finally {
                stopDiagnostics()
                baseStore.clearPendingPushWakeCatchUp()
                preferences.edit().clear().commit()
            }
        }

    /** A failed stale-identity restore is diagnosed without escaping non-cancellable settlement. */
    @Test
    fun failedStaleIdentityRestoreDoesNotThrow() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val preferences = context.getSharedPreferences("push-wake-failed-restore", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val baseStore = PushTokenStore(preferences)
            val nativeReturned = AtomicBoolean(false)
            val postNativeDispatches = AtomicInteger()
            val clearQueued = CountDownLatch(1)
            val releaseClear = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "push-wake-failed-restore-test") }
            val dispatcher = executor.asCoroutineDispatcher()
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    emitStartupNotification = false,
                    onCatchUpAccounts = { nativeReturned.set(true) },
                    pushWakeStorageDispatcher =
                        blockingClearDispatcher(
                            dispatcher = dispatcher,
                            nativeReturned = nativeReturned,
                            postNativeDispatches = postNativeDispatches,
                            clearQueued = clearQueued,
                            releaseClear = releaseClear,
                        ),
                )
            try {
                fixture.bootstrap()
                baseStore.recordPendingPushWakeCatchUp()
                fixture.replacePushTokenStore(PushTokenStore(FailingRestoreAfterClearPreferences(preferences)))
                startDiagnostics()
                val recovery = async { runCatching { fixture.awaitPushDrain(100L) } }
                withTimeout(5_000L) {
                    while (clearQueued.count > 0L) delay(10L)
                }
                fixture.runWithMainLooperPumping {
                    withContext(Dispatchers.Main.immediate) { fixture.advanceNetworkIdentity() }
                }
                releaseClear.countDown()

                val failure = withTimeout(5_000L) { recovery.await() }.exceptionOrNull()
                assertEquals("push wake account catch-up incomplete", failure?.message)
                assertPersistenceFailureLogged()
            } finally {
                releaseClear.countDown()
                stopDiagnostics()
                baseStore.clearPendingPushWakeCatchUp()
                fixture.close()
                dispatcher.close()
                executor.shutdownNow()
                preferences.edit().clear().commit()
            }
        }

    /** Starts one privacy-safe push trace for persistence diagnostics. */
    private fun startDiagnostics() {
        ShadowLog.clear()
        PerformanceDiagnostics.start()
        PushWakeDiagnostics.received(PushWakePriority.Normal, PushWakePriority.Normal, deleted = false)
    }

    /** Releases the process-global diagnostics owner after every injected failure. */
    private fun stopDiagnostics() {
        PerformanceDiagnostics.stop()
        PushWakeDiagnostics.complete()
    }

    /** Confirms the failure is reported without inspecting account or message identifiers. */
    private fun assertPersistenceFailureLogged() {
        assertTrue(ShadowLog.getLogsForTag("WNPerf").any { "phase=push_persistence_failed" in it.msg })
    }

    /** Holds the marker-clear dispatch so the test can invalidate its network identity. */
    private fun blockingClearDispatcher(
        dispatcher: CoroutineDispatcher,
        nativeReturned: AtomicBoolean,
        postNativeDispatches: AtomicInteger,
        clearQueued: CountDownLatch,
        releaseClear: CountDownLatch,
    ): CoroutineDispatcher =
        object : CoroutineDispatcher() {
            override fun dispatch(
                context: kotlin.coroutines.CoroutineContext,
                block: Runnable,
            ) {
                dispatcher.dispatch(context) {
                    if (nativeReturned.get() && postNativeDispatches.incrementAndGet() == 2) {
                        clearQueued.countDown()
                        check(releaseClear.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    block.run()
                }
            }
        }

    /** Rejects only the attempt-admission commit without mutating the backing preferences. */
    private class FailingAdmissionCommitPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                private var recordsAttempt = false

                override fun putInt(
                    key: String?,
                    value: Int,
                ): SharedPreferences.Editor {
                    if (key == "push_wake_attempts") recordsAttempt = true
                    editor.putInt(key, value)
                    return this
                }

                override fun commit(): Boolean = if (recordsAttempt) false else editor.commit()
            }
        }
    }

    /** Rejects only attempt completion while retaining the pending wake and claimed attempt. */
    private class FailingCompletionCommitPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                private var removesAttempts = false
                private var removesRetryAt = false
                private var clearsPendingGeneration = false

                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key == "push_wake_attempts") removesAttempts = true
                    if (key == "push_wake_retry_at") removesRetryAt = true
                    if (key == "pending_push_wake_catch_up_generation") clearsPendingGeneration = true
                    editor.remove(key)
                    return this
                }

                override fun commit(): Boolean =
                    if (removesAttempts && removesRetryAt && !clearsPendingGeneration) {
                        false
                    } else {
                        editor.commit()
                    }
            }
        }
    }

    /** Commits the stale clear, then rejects only its replacement generation. */
    private class FailingRestoreAfterClearPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        private var clearCommitted = false

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                private var clearsPendingGeneration = false
                private var recordsPendingGeneration = false

                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key == "pending_push_wake_catch_up_generation") clearsPendingGeneration = true
                    editor.remove(key)
                    return this
                }

                override fun putLong(
                    key: String?,
                    value: Long,
                ): SharedPreferences.Editor {
                    if (key == "pending_push_wake_catch_up_generation") recordsPendingGeneration = true
                    editor.putLong(key, value)
                    return this
                }

                override fun commit(): Boolean {
                    if (clearCommitted && recordsPendingGeneration) return false
                    return editor.commit().also { committed ->
                        if (committed && clearsPendingGeneration) clearCommitted = true
                    }
                }
            }
        }
    }
}
