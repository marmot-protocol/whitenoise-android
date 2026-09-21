package dev.ipf.whitenoise.android.notifications

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Verifies that best-effort durable bookkeeping failures never mask recovery outcomes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushWakeBookkeepingFailureTest {
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
