package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises real AppState catch-up, durable wake storage, and notification posting without an Activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPushWakeCatchUpTest {
    private var previousKeepConnected = false
    private var recoveryNowMs = 1_000L

    /** Model the production incident explicitly; the platform preference defaults to enabled. */
    @Before
    fun useOneShotPushDelivery() {
        val context: Application = RuntimeEnvironment.getApplication()
        previousKeepConnected = BackgroundConnectionPreferences.isEnabled(context)
        BackgroundConnectionPreferences.setEnabled(context, false)
    }

    /** Restore platform preferences so this fixture cannot change another scenario's delivery mode. */
    @After
    fun restoreDeliveryMode() {
        val context: Application = RuntimeEnvironment.getApplication()
        BackgroundConnectionPreferences.setEnabled(context, previousKeepConnected)
    }

    /** Actual dispatch callbacks coalesce behind a held native fetch and a fresh successor clears the latest marker. */
    @Test
    fun hundredCallbacksDuringNativeFetchNeedOneFreshSuccessor() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            var calls = 0
            val states = mutableListOf(androidx.work.WorkInfo.State.RUNNING)
            var enqueues = 0
            val coordinator =
                PushWakeRecoveryCoordinator(store, { false }, { false }, {
                    PushWakeRecoveryScheduler.schedule(store, { states }) {
                        enqueues++
                        states += androidx.work.WorkInfo.State.BLOCKED
                    }
                })
            val fixture =
                NotificationBootstrapTestFixture(context, emitStartupNotification = false, onCatchUpAccounts = {
                    calls++
                    if (calls == 1) {
                        started.complete(Unit)
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                })
            try {
                store.recordPendingPushWakeCatchUp()
                val first = async { fixture.awaitPushDrain(100L) }
                kotlinx.coroutines.withTimeout(5_000L) { started.await() }
                repeat(100) { coordinator.receive(PushWakePriority.Normal) }
                val newest = store.pendingPushWakeCatchUpGeneration()
                assertEquals(1, enqueues)
                release.countDown()
                first.await()
                assertEquals(newest, store.pendingPushWakeCatchUpGeneration())
                fixture.awaitPushDrain(100L)
                assertFalse(store.pushWakeCatchUpPending())
                assertEquals(2, calls)
            } finally {
                release.countDown()
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Service and worker overlap at the shared production boundary without duplicating native catch-up. */
    @Test
    fun simultaneousRecoveryOwnersShareOneNativeAttempt() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val calls = AtomicInteger()
            lateinit var fixture: NotificationBootstrapTestFixture
            fixture =
                NotificationBootstrapTestFixture(context, emitStartupNotification = false, onCatchUpAccounts = {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    fixture.emitNotification()
                })
            try {
                store.recordPendingPushWakeCatchUp()
                val serviceOwner =
                    async { fixture.runWithMainLooperPumping { fixture.appState.runPushWakeRecoveryAttempt() } }
                withTimeout(5_000L) { started.await() }
                val workerOwner =
                    async { fixture.runWithMainLooperPumping { fixture.appState.runPushWakeRecoveryAttempt() } }
                delay(50L)
                assertEquals(1, calls.get())
                release.countDown()
                withTimeout(5_000L) {
                    serviceOwner.await()
                    workerOwner.await()
                }
                assertEquals(1, calls.get())
                assertFalse(store.pushWakeCatchUpPending())
                assertEquals(0, store.pushWakeAttempts())
            } finally {
                release.countDown()
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** A saved Keep connected preference cannot impersonate a foreground-service lifecycle. */
    @Test
    fun savedPreferenceWithoutLiveServiceDoesNotAcceptWake() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            BackgroundConnectionPreferences.setEnabled(context, true)
            val store = PushTokenStore.create(context)
            val fixture = NotificationBootstrapTestFixture(context, emitStartupNotification = false)
            try {
                fixture.bootstrap()
                store.recordPendingPushWakeCatchUp()
                val accepted =
                    fixture.runWithMainLooperPumping {
                        withContext(Dispatchers.Main.immediate) { fixture.appState.acceptPushWakeRecovery() }
                    }

                assertFalse(accepted)
                assertTrue(store.pushWakeCatchUpPending())
            } finally {
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Releasing the acknowledged service owner immediately confirms a durable successor. */
    @Test
    fun foregroundServiceOwnerLossTransfersPendingWakeToScheduler() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            val schedules = AtomicInteger()
            val serviceOwner = Any()
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    emitStartupNotification = false,
                    schedulePushWakeRecovery = {
                        schedules.incrementAndGet()
                        true
                    },
                )
            try {
                fixture.bootstrap()
                store.recordPendingPushWakeCatchUp()
                fixture.runWithMainLooperPumping {
                    withContext(Dispatchers.Main.immediate) {
                        fixture.appState.acknowledgePushWakeServiceOwner(serviceOwner)
                        assertTrue(fixture.appState.acceptPushWakeRecovery())
                        fixture.appState.releasePushWakeServiceOwner(serviceOwner)
                    }
                }
                withTimeout(5_000L) {
                    while (schedules.get() == 0) delay(10L)
                }

                assertTrue(store.pushWakeCatchUpPending())
                assertEquals(1, schedules.get())
            } finally {
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Durable attempt and acknowledgement commits suspend recovery without blocking the main thread. */
    @Test
    fun durableBookkeepingRunsOffMainAndStillAcknowledgesGeneration() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            val storageEntered = CountDownLatch(1)
            val releaseStorage = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "push-wake-storage-test") }
            val storageDispatcher = executor.asCoroutineDispatcher()
            lateinit var fixture: NotificationBootstrapTestFixture
            fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    pushWakeStorageDispatcher =
                        object : kotlinx.coroutines.CoroutineDispatcher() {
                            override fun dispatch(
                                context: kotlin.coroutines.CoroutineContext,
                                block: Runnable,
                            ) {
                                storageDispatcher.dispatch(context) {
                                    storageEntered.countDown()
                                    check(releaseStorage.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                    block.run()
                                }
                            }
                        },
                    emitStartupNotification = false,
                    onCatchUpAccounts = { fixture.emitNotification() },
                )
            try {
                fixture.bootstrap()
                store.recordPendingPushWakeCatchUp()
                val recovery =
                    async { fixture.runWithMainLooperPumping { fixture.appState.runPushWakeRecoveryAttempt() } }
                withTimeout(5_000L) {
                    while (storageEntered.count > 0L) delay(10L)
                }

                var mainResponded = false
                fixture.runWithMainLooperPumping {
                    withContext(Dispatchers.Main.immediate) { mainResponded = true }
                }
                assertTrue(mainResponded)
                assertFalse(recovery.isCompleted)

                releaseStorage.countDown()
                withTimeout(5_000L) { recovery.await() }
                assertFalse(store.pushWakeCatchUpPending())
                assertEquals(0, store.pushWakeAttempts())
            } finally {
                releaseStorage.countDown()
                store.clearPendingPushWakeCatchUp()
                fixture.close()
                storageDispatcher.close()
                executor.shutdownNow()
            }
        }

    /** A network change suspended inside completion bookkeeping leaves the wake pending. */
    @Test
    fun networkChangeDuringCompletionCannotAcknowledgeOldCatchUp() {
        assertNetworkChangeDuringSettlementLeavesWakePending(heldPostNativeDispatch = 1)
    }

    /** A network change suspended inside marker acknowledgement restores the cleared wake. */
    @Test
    fun networkChangeDuringAcknowledgementCannotClearOldCatchUp() {
        assertNetworkChangeDuringSettlementLeavesWakePending(heldPostNativeDispatch = 2)
    }

    /** Cancellation after the clear commit still restores a wake whose network identity became stale. */
    @Test
    fun cancellationAfterAcknowledgementClearPreservesPendingWake() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val preferences = context.getSharedPreferences("push-wake-clear-cancellation", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val baseStore = PushTokenStore(preferences)
            baseStore.recordPendingPushWakeCatchUp()
            val clearCommitted = CountDownLatch(1)
            val releaseClear = CountDownLatch(1)
            val blockingPreferences =
                BlockingClearCommitPreferences(
                    delegate = preferences,
                    clearCommitted = clearCommitted,
                    releaseClear = releaseClear,
                )
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    emitStartupNotification = false,
                    onCatchUpAccounts = {},
                )
            try {
                fixture.bootstrap()
                fixture.replacePushTokenStore(PushTokenStore(blockingPreferences))
                val recovery = async { fixture.awaitPushDrain(100L) }
                withTimeout(5_000L) {
                    while (clearCommitted.count > 0L) delay(10L)
                }
                fixture.runWithMainLooperPumping {
                    withContext(Dispatchers.Main.immediate) { fixture.advanceNetworkIdentity() }
                }
                recovery.cancel()
                releaseClear.countDown()
                withTimeout(5_000L) { recovery.join() }

                assertTrue(recovery.isCancelled)
                assertTrue(baseStore.pushWakeCatchUpPending())
            } finally {
                releaseClear.countDown()
                baseStore.clearPendingPushWakeCatchUp()
                fixture.close()
                preferences.edit().clear().commit()
            }
        }

    /** Cancellation while restoration is queued cannot suppress the replacement durable generation. */
    @Test
    fun cancellationDuringAcknowledgementRestorePreservesPendingWake() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            val nativeReturned = AtomicBoolean(false)
            val postNativeDispatches = AtomicInteger()
            val clearQueued = CountDownLatch(1)
            val releaseClear = CountDownLatch(1)
            val restoreQueued = CountDownLatch(1)
            val releaseRestore = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "push-wake-cancellation-test") }
            val dispatcher = executor.asCoroutineDispatcher()
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    emitStartupNotification = false,
                    onCatchUpAccounts = { nativeReturned.set(true) },
                    pushWakeStorageDispatcher =
                        object : CoroutineDispatcher() {
                            override fun dispatch(
                                context: kotlin.coroutines.CoroutineContext,
                                block: Runnable,
                            ) {
                                dispatcher.dispatch(context) {
                                    when {
                                        !nativeReturned.get() -> Unit
                                        postNativeDispatches.incrementAndGet() == 2 -> {
                                            clearQueued.countDown()
                                            check(releaseClear.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                        }
                                        postNativeDispatches.get() == 3 -> {
                                            restoreQueued.countDown()
                                            check(releaseRestore.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                        }
                                    }
                                    block.run()
                                }
                            }
                        },
                )
            try {
                fixture.bootstrap()
                store.recordPendingPushWakeCatchUp()
                val recovery = async { fixture.awaitPushDrain(100L) }
                withTimeout(5_000L) {
                    while (clearQueued.count > 0L) delay(10L)
                }
                fixture.runWithMainLooperPumping {
                    withContext(Dispatchers.Main.immediate) { fixture.advanceNetworkIdentity() }
                }
                releaseClear.countDown()
                withTimeout(5_000L) {
                    while (restoreQueued.count > 0L) delay(10L)
                }
                recovery.cancel()
                releaseRestore.countDown()
                withTimeout(5_000L) { recovery.join() }

                assertTrue(recovery.isCancelled)
                assertTrue(store.pushWakeCatchUpPending())
            } finally {
                releaseClear.countDown()
                releaseRestore.countDown()
                store.clearPendingPushWakeCatchUp()
                fixture.close()
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    /** A stale FCM wake must not tear down a healthy user-enabled persistent receiver after a failed fetch. */
    @Test
    fun catchUpFailurePreservesKeepConnectedService() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            BackgroundConnectionPreferences.setEnabled(context, true)
            val store = PushTokenStore.create(context)
            var fetches = 0
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    pushWakeNowMs = { recoveryNowMs },
                    onCatchUpAccounts = {
                        fetches++
                        throw IOException("relay unavailable")
                    },
                    emitStartupNotification = false,
                )
            try {
                fixture.bootstrap()
                store.recordPendingPushWakeCatchUp()
                val generation = store.pendingPushWakeCatchUpGeneration()
                ShadowLog.clear()
                PerformanceDiagnostics.start()
                PushWakeDiagnostics.received(PushWakePriority.High, PushWakePriority.High, deleted = false)
                val outcome =
                    supervisor().supervise(
                        recoveryAllowed = { true },
                        startRuntime = { fixture.awaitPushDrain(100L) },
                    )

                assertEquals(NotificationRuntimeSupervisionOutcome.Started(1), outcome)
                assertEquals(1, fetches)
                assertEquals(generation, store.pendingPushWakeCatchUpGeneration())
                val diagnostics = ShadowLog.getLogsForTag("WNPerf").map { it.msg }
                assertTrue(diagnostics.any { "phase=push_attempt_failed" in it })
                assertFalse(diagnostics.any { "phase=push_attempt_succeeded" in it })
                assertFalse(
                    shouldStopAfterOneShotForegroundStart(
                        oneShotRequested = true,
                        backgroundConnectionEnabled = fixture.appState.backgroundConnectionEnabled,
                    ),
                )
            } finally {
                PerformanceDiagnostics.stop()
                PushWakeDiagnostics.complete()
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** A warm background process must recover from a relay failure before releasing its wake. */
    @Test
    fun warmWakeRetriesFailedFetchAndPostsMessageAndReaction() = runBlocking { assertTransientRecovery(warm = true) }

    /** The same retry contract applies when FCM is the first caller to bootstrap the process. */
    @Test
    fun coldWakeRetriesFailedFetchAndPostsMessageAndReaction() = runBlocking { assertTransientRecovery(warm = false) }

    /** A successful decoy or muted wake needs no visible update and must not trigger retries. */
    @Test
    fun successfulQuietCatchUpCompletesOnceWithoutNotification() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            var fetches = 0
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    pushWakeNowMs = { recoveryNowMs },
                    onCatchUpAccounts = { fetches++ },
                    emitStartupNotification = false,
                )
            try {
                store.recordPendingPushWakeCatchUp()
                var drained: Boolean? = null
                val outcome =
                    supervisor().supervise(
                        recoveryAllowed = { true },
                        startRuntime = { drained = fixture.awaitPushDrain(100L) },
                    )

                assertEquals(NotificationRuntimeSupervisionOutcome.Started(1), outcome)
                assertEquals(1, fetches)
                assertEquals(false, drained)
                assertFalse(store.pushWakeCatchUpPending())
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            } finally {
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Offline catch-up exhausts the existing retry budget and retains the wake for later recovery. */
    @Test
    fun repeatedFetchFailureRetainsDurableWakeAfterFourAttempts() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            var fetches = 0
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    pushWakeNowMs = { recoveryNowMs },
                    onCatchUpAccounts = {
                        fetches++
                        throw IOException("relay unavailable")
                    },
                    emitStartupNotification = false,
                )
            try {
                store.recordPendingPushWakeCatchUp()
                val generation = store.pendingPushWakeCatchUpGeneration()
                val outcome =
                    supervisor().supervise(
                        recoveryAllowed = { true },
                        startRuntime = { fixture.awaitPushDrain(100L) },
                    )

                assertTrue(outcome is NotificationRuntimeSupervisionOutcome.Exhausted)
                assertEquals(4, fetches)
                assertEquals(generation, store.pendingPushWakeCatchUpGeneration())
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            } finally {
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Successful work for an older wake must leave a concurrently received wake pending. */
    @Test
    fun newerWakeSurvivesSuccessfulCatchUp() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    pushWakeNowMs = { recoveryNowMs },
                    onCatchUpAccounts = { store.recordPendingPushWakeCatchUp() },
                    emitStartupNotification = false,
                )
            try {
                store.recordPendingPushWakeCatchUp()
                val generation = store.pendingPushWakeCatchUpGeneration()
                val outcome =
                    supervisor().supervise(
                        recoveryAllowed = { true },
                        startRuntime = { fixture.awaitPushDrain(100L) },
                    )

                assertEquals(NotificationRuntimeSupervisionOutcome.Started(1), outcome)
                assertTrue(store.pendingPushWakeCatchUpGeneration() > generation)
            } finally {
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Injects failure at the native fetch boundary, then verifies both Android notification types. */
    private suspend fun assertTransientRecovery(warm: Boolean) {
        val context: Application = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        val store = PushTokenStore.create(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var fetches = 0
        lateinit var fixture: NotificationBootstrapTestFixture
        fixture =
            NotificationBootstrapTestFixture(
                context,
                pushWakeNowMs = { recoveryNowMs },
                onCatchUpAccounts = {
                    fetches++
                    if (fetches == 1) throw IOException("transient relay failure")
                    fixture.emitNotification()
                    fixture.emitNotification(
                        fixture.update.copy(
                            notificationKey = "reaction:account-a:message-a",
                            reactionEmoji = "👍",
                            reactedToPreview = "Original message",
                        ),
                    )
                },
                emitStartupNotification = false,
            )
        try {
            if (warm) fixture.bootstrap()
            store.recordPendingPushWakeCatchUp()
            val outcome =
                supervisor().supervise(
                    recoveryAllowed = { true },
                    startRuntime = { fixture.awaitPushDrain(1_000L) },
                )

            assertEquals(NotificationRuntimeSupervisionOutcome.Started(2), outcome)
            assertEquals(2, fetches)
            assertFalse(store.pushWakeCatchUpPending())
            withTimeout(5_000L) {
                while (manager.activeNotifications.map { it.id }.toSet() != setOf(0, 1)) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                    delay(1L)
                }
            }
        } finally {
            manager.cancelAll()
            store.clearPendingPushWakeCatchUp()
            fixture.close()
        }
    }

    /** Keeps production retry limits while removing wall-clock backoff from the regression. */
    private fun supervisor() = NotificationRuntimeSupervisor(waitBeforeRetry = { recoveryNowMs += 240_000L })

    /** Holds a selected durable dispatch after native success and invalidates its network identity. */
    private fun assertNetworkChangeDuringSettlementLeavesWakePending(heldPostNativeDispatch: Int) =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            val nativeReturned = AtomicBoolean(false)
            val postNativeDispatches = AtomicInteger()
            val settlementEntered = CountDownLatch(1)
            val releaseSettlement = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "push-wake-settlement-test") }
            val dispatcher = executor.asCoroutineDispatcher()
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    emitStartupNotification = false,
                    onCatchUpAccounts = { nativeReturned.set(true) },
                    pushWakeStorageDispatcher =
                        object : CoroutineDispatcher() {
                            override fun dispatch(
                                context: kotlin.coroutines.CoroutineContext,
                                block: Runnable,
                            ) {
                                dispatcher.dispatch(context) {
                                    val shouldHold =
                                        nativeReturned.get() &&
                                            postNativeDispatches.incrementAndGet() == heldPostNativeDispatch
                                    if (shouldHold) {
                                        settlementEntered.countDown()
                                        check(releaseSettlement.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                    }
                                    block.run()
                                }
                            }
                        },
                )
            try {
                fixture.bootstrap()
                store.recordPendingPushWakeCatchUp()
                val recovery = async { runCatching { fixture.awaitPushDrain(100L) } }
                withTimeout(5_000L) {
                    while (settlementEntered.count > 0L) delay(10L)
                }
                fixture.runWithMainLooperPumping {
                    withContext(Dispatchers.Main.immediate) { fixture.advanceNetworkIdentity() }
                }
                releaseSettlement.countDown()

                assertTrue(withTimeout(5_000L) { recovery.await() }.isFailure)
                assertTrue(store.pushWakeCatchUpPending())
                assertFalse(fixture.startupRelayCatchUpRecorded())
            } finally {
                releaseSettlement.countDown()
                store.clearPendingPushWakeCatchUp()
                fixture.close()
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    /** Blocks immediately after the observed marker clear has committed. */
    private class BlockingClearCommitPreferences(
        private val delegate: SharedPreferences,
        private val clearCommitted: CountDownLatch,
        private val releaseClear: CountDownLatch,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                private var clearingObservedGeneration = false

                override fun remove(key: String?): SharedPreferences.Editor {
                    if (key == "pending_push_wake_catch_up_generation") clearingObservedGeneration = true
                    editor.remove(key)
                    return this
                }

                override fun commit(): Boolean {
                    val committed = editor.commit()
                    if (clearingObservedGeneration) {
                        clearCommitted.countDown()
                        check(releaseClear.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return committed
                }
            }
        }
    }
}
