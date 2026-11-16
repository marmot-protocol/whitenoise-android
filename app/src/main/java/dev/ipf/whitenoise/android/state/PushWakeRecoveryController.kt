package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrigger
import dev.ipf.whitenoise.android.notifications.NotificationCatchUpWindow
import dev.ipf.whitenoise.android.notifications.PUSH_WAKE_MAX_ATTEMPTS
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import dev.ipf.whitenoise.android.notifications.PushWakeAdmission
import dev.ipf.whitenoise.android.notifications.PushWakeAttemptBudget
import dev.ipf.whitenoise.android.notifications.PushWakeAttemptClaim
import dev.ipf.whitenoise.android.notifications.PushWakeDiagnostics
import dev.ipf.whitenoise.android.notifications.PushWakeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** App-owned state and runtime hooks that push-wake recovery reads without owning. */
internal interface PushWakeRecoveryHost {
    val activeAccountRef: String?
    val runtimeGeneration: Int
    val phase: AppPhase
    val appInForeground: Boolean
    val bootstrapCompleted: Boolean
    val networkNotificationRecoverySuppressed: Boolean
    val validatedConnectivityRecoveryGeneration: Long
    val networkRecoveryActive: Boolean

    fun captureNetworkGeneration(): Long

    fun isNetworkGenerationCurrent(generation: Long): Boolean

    fun recordStartupRelayCatchUpReady()

    suspend fun <T> marmotIo(
        traceSection: String,
        block: suspend MarmotInterface.() -> T,
    ): T

    fun notificationRuntimeRecoveryGeneration(): Long

    fun notificationRuntimeRecoveryAllowed(generation: Long): Boolean

    suspend fun ensureNotificationRuntimeStartedAndAwaitPushDrain(): Boolean

    suspend fun ensureNotificationRuntimeStarted(): Boolean

    suspend fun syncNativePushRegistrationIfEnabled(): Boolean
}

/**
 * Owns push-wake recovery and shared account catch-up.
 *
 * One coordinator serializes native catch-up for every trigger (explicit, chat list, foreground, network reconnect,
 * push wake). Push-wake episodes share a durable attempt budget, a process-owned drain accepted only while an Activity
 * or foreground service owns the runtime, and a circuit that keeps network recovery and the independent drain from
 * both consuming one wake.
 */
internal class PushWakeRecoveryController(
    private val pushTokenStore: PushTokenStore,
    private val pushWakeStorageDispatcher: CoroutineDispatcher,
    private val pushWakeNowMs: () -> Long,
    private val schedulePushWakeRecovery: () -> Boolean,
    private val notificationScope: CoroutineScope,
    catchUpWindow: NotificationCatchUpWindow,
    private val host: PushWakeRecoveryHost,
) {
    private val accountCatchUpCoordinator = AccountCatchUpCoordinator(notificationScope, catchUpWindow)
    private val pushWakeCatchUpDrainJob = NotificationJobSlot()
    private val notificationPushWakeRecoveryCircuit = NotificationPushWakeRecoveryCircuit()

    @Volatile
    private var isForegroundCatchUpRunning = false

    private fun pushWakeAttemptBudget() =
        PushWakeAttemptBudget(
            store = pushTokenStore,
            storageDispatcher = pushWakeStorageDispatcher,
            nowMs = pushWakeNowMs,
        )

    /** Reports whether a foreground-service lease currently owns background runtime. */
    val pushWakeServiceOwned: Boolean get() = pushWakeServiceOwner != null

    /** Reports whether an independent push-wake drain is still running. */
    fun isDrainActive(): Boolean = pushWakeCatchUpDrainJob.isActive()

    /** Cancels the independent push-wake drain and waits for it to settle. */
    suspend fun cancelDrainAndJoin() = pushWakeCatchUpDrainJob.cancelAndJoin()

    /** Network reconnect catch-up acknowledges a push-wake marker only if one was readable. */
    internal suspend fun catchUpAfterNetworkReconnect(): AccountCatchUpResult {
        val pendingGeneration =
            withContext(pushWakeStorageDispatcher) {
                observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }
            } ?: 0L
        // Unknown push storage cannot prevent ordinary relay catch-up or authorize marker acknowledgement.
        return catchUpAfterObservedPushWake(
            pendingGeneration = pendingGeneration,
            trigger = PerformanceTrigger.NETWORK_RECONNECT,
        )
    }

    /** Records that network recovery owns the current push-wake generation for this network edge. */
    fun noteNetworkRecoveryAttempt(networkGeneration: Long) {
        observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }?.let { generation ->
            notificationPushWakeRecoveryCircuit.noteRecoveryAttempt(
                networkGeneration = networkGeneration,
                pushWakeGeneration = generation,
            )
        }
    }

    /** Lets an independent drain retry once network recovery gives up on this network edge. */
    fun noteNetworkRecoveryExhausted(networkGeneration: Long) {
        notificationPushWakeRecoveryCircuit.noteRecoveryExhausted(networkGeneration)
    }

    /**
     * Drive Marmot's per-account catch-up so every signed-in account on this
     * device processes the events its worker has pending — most importantly
     * the MLS commits / kind-1210 group-system rows that peers (including the
     * device's *other* local accounts) published while it was inactive.
     *
     * The engine owns one SQLite store per account-device identity, so an
     * inactive account never sees a sibling account's group rename, avatar
     * change, or membership commit until its own worker ingests that event.
     * `catchUpAccounts` pumps all running workers, so calling it before we
     * read an account's projection makes that account's source-of-truth store
     * current first — rather than caching the change Android-side (which the
     * repo's source-of-truth rule forbids). See issue #252 (group rename not
     * propagating to a second same-device account) and the adjacent
     * convergence cases #107 / #116 / #151.
     *
     * Best-effort: catch-up is a relay round-trip that can be slow or fail
     * offline, and a read surface must still render its last-known projection
     * in that case. Failures are swallowed (cancellation re-thrown) so a
     * caller can always `await` this without it becoming a hard gate.
     */
    internal suspend fun catchUpAccounts() {
        launchAccountCatchUp(
            mustStartAfter = null,
            trigger = PerformanceTrigger.EXPLICIT,
        ).await()
    }

    /**
     * Starts a process-owned catch-up after a chat list has rendered its local
     * snapshot. A blocked relay call must not block that controller's live
     * consumers. Callers for the same account, runtime, and network share one
     * in-flight result; a newer identity waits instead of overlapping native work.
     */
    internal fun launchCatchUpAccounts(): Deferred<AccountCatchUpResult> =
        launchAccountCatchUp(
            mustStartAfter = null,
            trigger = PerformanceTrigger.CHAT_LIST_READINESS,
        )

    /** Builds the current catch-up identity and serializes its native work process-wide. */
    private fun launchAccountCatchUp(
        mustStartAfter: Long?,
        trigger: PerformanceTrigger,
        publishReadiness: Boolean = true,
    ): Deferred<AccountCatchUpResult> =
        AccountCatchUpKey(
            accountRef = host.activeAccountRef,
            runtimeGeneration = host.runtimeGeneration,
            networkGeneration = host.captureNetworkGeneration(),
        ).let { key ->
            val catchUp: suspend () -> Boolean = {
                performAccountCatchUp(key, trigger).also { succeeded ->
                    if (succeeded && publishReadiness) host.recordStartupRelayCatchUpReady()
                }
            }
            if (mustStartAfter == null) {
                accountCatchUpCoordinator.launch(key, catchUp)
            } else {
                accountCatchUpCoordinator.launchAfter(mustStartAfter, key, catchUp)
            }
        }

    /** Checks queued work again at native admission and settlement, including destructive teardown. */
    private fun isCatchUpKeyCurrent(key: AccountCatchUpKey): Boolean =
        host.activeAccountRef == key.accountRef &&
            host.runtimeGeneration == key.runtimeGeneration &&
            host.isNetworkGenerationCurrent(key.networkGeneration) &&
            !host.networkNotificationRecoverySuppressed

    /** Charges only executed native work; every owner shares this durable failure budget. */
    private suspend fun performAccountCatchUp(
        key: AccountCatchUpKey,
        trigger: PerformanceTrigger,
    ): Boolean =
        if (!isCatchUpKeyCurrent(key)) {
            false
        } else {
            val admission =
                observePushState { pushWakeAttemptBudget().reserve() }
                    ?: if (trigger == PerformanceTrigger.PUSH_WAKE) {
                        PushWakeAdmission.Rejected
                    } else {
                        // Ordinary foreground/connectivity work is independently bounded
                        // and cannot settle an unknown wake.
                        PushWakeAdmission.Admitted(claim = null)
                    }
            when (admission) {
                PushWakeAdmission.Rejected -> false
                is PushWakeAdmission.Admitted -> {
                    if (isCatchUpKeyCurrent(key)) {
                        performAdmittedAccountCatchUp(key, trigger, admission.claim)
                    } else {
                        admission.claim?.let { reserved ->
                            observePushState { pushWakeAttemptBudget().release(reserved) }
                        }
                        false
                    }
                }
            }
        }

    /** Runs native catch-up only after admission and settles the same durable reservation. */
    private suspend fun performAdmittedAccountCatchUp(
        key: AccountCatchUpKey,
        trigger: PerformanceTrigger,
        claim: PushWakeAttemptClaim?,
    ): Boolean {
        val nativeSucceeded = instrumentedCatchUpAccounts(trigger)
        var settlementSucceeded = nativeSucceeded && isCatchUpKeyCurrent(key)
        if (claim != null) {
            settlementSucceeded =
                observePushState {
                    pushWakeAttemptBudget().settle(nativeSucceeded) { isCatchUpKeyCurrent(key) }
                } == true
        }
        val succeeded = settlementSucceeded && isCatchUpKeyCurrent(key)
        if (claim != null) {
            PushWakeDiagnostics.event(if (succeeded) PushWakeEvent.AttemptSucceeded else PushWakeEvent.AttemptFailed)
        }
        return succeeded
    }

    /** Runs catch-up work fresh enough to acknowledge the observed push-wake marker. */
    private suspend fun catchUpAfterObservedPushWake(
        pendingGeneration: Long,
        trigger: PerformanceTrigger,
    ): AccountCatchUpResult {
        if (pendingGeneration == 0L) {
            return launchAccountCatchUp(mustStartAfter = null, trigger = trigger).await()
        }
        val observedStartSequence = accountCatchUpCoordinator.captureStartSequence()
        val result =
            runCatchUpAfterTrigger(
                observedStartSequence = observedStartSequence,
                launchAfter = { sequence ->
                    launchAccountCatchUp(
                        mustStartAfter = sequence,
                        trigger = trigger,
                        publishReadiness = false,
                    )
                },
                onSucceeded = { result ->
                    observePushState {
                        result.key?.let { key -> acknowledgePendingPushWakeCatchUp(pendingGeneration, key) } == true
                    } == true
                },
            )
        if (result.succeeded) host.recordStartupRelayCatchUpReady()
        return result
    }

    /** Measures only native catch-up work admitted by the single-flight coordinator. */
    private suspend fun instrumentedCatchUpAccounts(trigger: PerformanceTrigger): Boolean {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.SYNC_CATCH_UP, trigger)
        PerformanceDiagnostics.record(
            trace = trace,
            phase = PerformancePhase.ACCOUNT_CATCH_UP_START,
            elapsedMs = 0L,
            result = PerformanceResult.PENDING,
            layer = PerformanceLayer.MDK,
        )
        val succeeded = RecoveryTrace.catchUp(trigger) { catchUpAccountsBestEffort() }
        val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
        PerformanceDiagnostics.record(
            trace = trace,
            phase =
                if (succeeded) {
                    PerformancePhase.ACCOUNT_CATCH_UP_READY
                } else {
                    PerformancePhase.ACCOUNT_CATCH_UP_RETRY
                },
            elapsedMs = elapsedMs,
            durationMs = elapsedMs,
            result = if (succeeded) PerformanceResult.SUCCESS else PerformanceResult.FAILURE,
            layer = PerformanceLayer.MDK,
        )
        return succeeded
    }

    private suspend fun catchUpAccountsBestEffort(): Boolean =
        runCatchingCancellable { host.marmotIo(MarmotTraceSection.CATCH_UP) { catchUpAccounts() } }
            .onFailure {
                appStateDebug(it) { "catchUpAccounts failed: ${it.readableMessage()}" }
            }.isSuccess

    /**
     * Best-effort account catch-up when the app returns to the foreground.
     * Mirrors iOS `catchUpAfterForegroundActivation`: pumps every running worker
     * so relay-ingested commits and projections converge without requiring an
     * account switch or process restart. Failures are swallowed; cancellation
     * propagates.
     */
    internal suspend fun catchUpAfterForegroundActivation() {
        if (
            !ForegroundCatchUpPolicy.shouldCatchUp(
                appPhase = host.phase,
                isCatchUpRunning = isForegroundCatchUpRunning,
                appInForeground = host.appInForeground,
            )
        ) {
            return
        }
        admitPushWakeEpisodeFromLifecycle()
        val pendingGeneration =
            withContext(pushWakeStorageDispatcher) {
                observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }
            } ?: 0L // Unknown storage must not acknowledge a saved generation.
        isForegroundCatchUpRunning = true
        try {
            catchUpAfterObservedPushWake(
                pendingGeneration = pendingGeneration,
                trigger = PerformanceTrigger.FOREGROUND,
            )
        } finally {
            isForegroundCatchUpRunning = false
        }
    }

    private val pushWakeAttemptMutex = kotlinx.coroutines.sync.Mutex()
    private var acceptedPushWakeJob: Job? = null
    private var acceptedPushWakeOwner: Any? = null
    private var pushWakeServiceOwner: Any? = null

    /** Fences teardown while preserving existing screen-lock notification redaction. */
    internal fun pushWakeRecoveryAllowed(): Boolean = !host.networkNotificationRecoverySuppressed

    /** Keeps storage failure in recovery bookkeeping from interrupting unlock or connectivity handling. */
    private suspend fun admitPushWakeEpisodeFromLifecycle() {
        val admitted =
            withContext(pushWakeStorageDispatcher) {
                observePushState { pushTokenStore.admitPushWakeEpisode(pushWakeNowMs()) }
            }
        if (admitted != true) {
            PushWakeDiagnostics.event(PushWakeEvent.PersistenceFailed)
        }
    }

    /** Acknowledges the concrete foreground-service instance that currently owns background runtime. */
    internal fun acknowledgePushWakeServiceOwner(owner: Any) {
        assertMainThread { "acknowledgePushWakeServiceOwner" }
        pushWakeServiceOwner = owner
    }

    /** Releases only the matching service lease and hands unresolved foreground work to durable scheduling. */
    internal fun releasePushWakeServiceOwner(owner: Any) {
        assertMainThread { "releasePushWakeServiceOwner" }
        if (pushWakeServiceOwner !== owner) return
        pushWakeServiceOwner = null
        transferUnownedPushWakeRecovery()
    }

    /** Accepts a process-owned drain only while an Activity or acknowledged service owns its lifetime. */
    internal fun acceptPushWakeRecovery(): Boolean {
        assertMainThread { "acceptPushWakeRecovery" }
        val ownsRuntime = host.bootstrapCompleted && (host.appInForeground || pushWakeServiceOwner != null)
        return when {
            !pushWakeRecoveryAllowed() || !ownsRuntime -> false
            acceptedPushWakeJob?.isActive == true -> acceptedPushWakeOwner != null
            else -> {
                val owner = Any()
                acceptedPushWakeOwner = owner
                acceptedPushWakeJob =
                    notificationScope.launch {
                        try {
                            runPushWakeRecoveryAttempt()
                        } finally {
                            if (acceptedPushWakeOwner === owner) {
                                acceptedPushWakeOwner = null
                                withContext(kotlinx.coroutines.NonCancellable + pushWakeStorageDispatcher) {
                                    schedulePushWakeRecovery()
                                }
                            }
                        }
                    }
                true
            }
        }
    }

    /** Revokes process ownership at its lifecycle boundary and immediately confirms a durable successor. */
    internal fun transferUnownedPushWakeRecovery() {
        if (host.appInForeground || pushWakeServiceOwner != null) return
        acceptedPushWakeOwner = null
        acceptedPushWakeJob?.cancel()
        acceptedPushWakeJob = null
        notificationScope.launch(pushWakeStorageDispatcher) { schedulePushWakeRecovery() }
    }

    /**
     * Shares service/worker ownership; cancellation leaves native settlement with AccountCatchUpCoordinator.
     * Native/platform bootstrap failures preserve the shared retry obligation.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun runPushWakeRecoveryAttempt() {
        pushWakeAttemptMutex.lock()
        try {
            val recoveryState =
                withContext(pushWakeStorageDispatcher) {
                    Triple(
                        pushTokenStore.pushWakeCatchUpPending(),
                        pushTokenStore.pushWakeAttempts(),
                        pushTokenStore.pushWakeRetryDelay(pushWakeNowMs()),
                    )
                }
            if (!pushWakeRecoveryAllowed() || !recoveryState.first) return
            val generation = host.notificationRuntimeRecoveryGeneration()
            val attempts = recoveryState.second
            if (attempts >= PUSH_WAKE_MAX_ATTEMPTS || recoveryState.third > 0L) return
            PushWakeDiagnostics.event(PushWakeEvent.AttemptStarted)
            try {
                host.ensureNotificationRuntimeStartedAndAwaitPushDrain()
                val pending = withContext(pushWakeStorageDispatcher) { pushTokenStore.pushWakeCatchUpPending() }
                if (host.notificationRuntimeRecoveryAllowed(generation) && !pending) {
                    PushWakeDiagnostics.complete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Receiver/bootstrap failure must also consume this same finite episode.
                if (host.notificationRuntimeRecoveryAllowed(generation)) {
                    withContext(pushWakeStorageDispatcher) {
                        if (pushTokenStore.pushWakeAttempts() == attempts) {
                            val reclaimed = pushTokenStore.claimPushWakeAttempt(pushWakeNowMs())
                            if (reclaimed == null || !pushTokenStore.deferPushWakeRetry(pushWakeNowMs())) {
                                PushWakeDiagnostics.event(PushWakeEvent.PersistenceFailed)
                            }
                        }
                    }
                }
                PushWakeDiagnostics.event(PushWakeEvent.AttemptFailed)
                throw error
            }
        } finally {
            pushWakeAttemptMutex.unlock()
        }
    }

    /** Retries a persisted token-registration obligation when notification startup reaches a usable runtime. */
    internal suspend fun drainPendingNativePushRegistrationSyncIfNeeded() {
        val pending =
            withContext(pushWakeStorageDispatcher) {
                observePushState {
                    pushTokenStore.nativePushRegistrationSyncPending() || pushTokenStore.lastToken() != null
                }
            } ?: return
        if (pending) {
            host.syncNativePushRegistrationIfEnabled()
        }
    }

    /** Returns fetch success, acknowledging only the matching durable wake after fresh work. */
    internal suspend fun drainPendingPushWakeCatchUpIfNeeded(): Boolean {
        val pendingGeneration =
            withContext(pushWakeStorageDispatcher) {
                observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }
            } ?: return false
        return pendingGeneration == 0L ||
            catchUpAfterObservedPushWake(
                pendingGeneration = pendingGeneration,
                trigger = PerformanceTrigger.PUSH_WAKE,
            ).succeeded
    }

    /**
     * Clears only work whose recovery identity stays current across the durable acknowledgement commit.
     * Cancellation is deferred across this bounded transaction so a committed clear cannot skip marker restoration.
     */
    private suspend fun acknowledgePendingPushWakeCatchUp(
        pendingGeneration: Long,
        key: AccountCatchUpKey,
    ): Boolean {
        if (pendingGeneration == 0L || !isCatchUpKeyCurrent(key)) return false
        val acknowledged =
            withContext(NonCancellable) {
                val cleared =
                    withContext(pushWakeStorageDispatcher) {
                        pushTokenStore.clearPendingPushWakeCatchUp(pendingGeneration)
                    }
                if (!isCatchUpKeyCurrent(key)) {
                    if (cleared) {
                        val restored =
                            withContext(pushWakeStorageDispatcher) {
                                pushTokenStore.recordPendingPushWakeCatchUp()
                            }
                        if (!restored) PushWakeDiagnostics.event(PushWakeEvent.PersistenceFailed)
                    }
                    false
                } else {
                    if (cleared) {
                        appStateDebug { "pending push wake catch-up drained" }
                    } else {
                        appStateDebug { "newer pending push wake catch-up remains queued" }
                    }
                    true
                }
            }
        currentCoroutineContext().ensureActive()
        return acknowledged
    }

    /** Starts push-wake catch-up only when network recovery does not own the receiver. */
    internal fun schedulePendingPushWakeCatchUpDrain() {
        val pendingPushWakeGeneration =
            observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() } ?: return
        if (pendingPushWakeGeneration != 0L && !host.networkNotificationRecoverySuppressed) {
            notificationScope.launch(pushWakeStorageDispatcher) { schedulePushWakeRecovery() }
        }
        val networkGeneration = host.validatedConnectivityRecoveryGeneration
        val runtimeUnavailable =
            host.networkNotificationRecoverySuppressed ||
                pendingPushWakeGeneration == 0L ||
                host.networkRecoveryActive
        val triggerAlreadyConsumed =
            !notificationPushWakeRecoveryCircuit.allowsIndependentDrain(
                networkGeneration = networkGeneration,
                pushWakeGeneration = pendingPushWakeGeneration,
            )
        if (runtimeUnavailable || triggerAlreadyConsumed) {
            return
        }
        pushWakeCatchUpDrainJob.startIfInactive {
            notificationScope
                .launch {
                    if (
                        notificationPushWakeRecoveryCircuit.claimIndependentDrain(
                            networkGeneration = networkGeneration,
                            pushWakeGeneration = pendingPushWakeGeneration,
                        )
                    ) {
                        host.ensureNotificationRuntimeStarted()
                    }
                }.also { drainJob ->
                    drainJob.invokeOnCompletion {
                        val currentPushWakeGeneration =
                            observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }
                                ?: return@invokeOnCompletion
                        val currentNetworkGeneration = host.validatedConnectivityRecoveryGeneration
                        if (
                            currentPushWakeGeneration != 0L &&
                            (
                                currentPushWakeGeneration != pendingPushWakeGeneration ||
                                    currentNetworkGeneration != networkGeneration
                            )
                        ) {
                            notificationScope.launch {
                                // Completion can run inline while the job slot lock is held.
                                yield()
                                schedulePendingPushWakeCatchUpDrain()
                            }
                        }
                    }
                }
        }
    }

    /** Reopens an exhausted episode off-main before evaluating process and durable owners. */
    internal fun resumePushWakeEpisodeFromLifecycle() {
        notificationScope.launch {
            admitPushWakeEpisodeFromLifecycle()
            schedulePendingPushWakeCatchUpDrain()
        }
    }
}
