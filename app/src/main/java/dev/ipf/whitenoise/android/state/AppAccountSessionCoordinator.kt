package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Caller intent for one account selection; the coordinator supplies its lifetime token. */
internal data class AccountSessionActivationRequest(
    val label: String,
    val deferUnreadRefresh: Boolean = false,
    val shouldActivate: () -> Boolean = { true },
    val preloadPolicy: AccountSwitchPreloadPolicy = AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT,
)

/** Receipt of the exact selection that reached local-ready, retained across any later enrichment waits. */
internal data class AccountSessionActivationReceipt(
    val label: String,
    val generation: Long,
    val runtimeGeneration: Int,
)

/** Short-lived authoritative projection prepared for the existing one-shot handoff, never a second store. */
internal data class PreparedAccountSessionActivation(
    val runtimeGeneration: Int,
    val profilePresentation: StartupSelfProfilePresentation,
    val snapshot: AccountSwitchLocalSnapshot?,
)

/** Android/native adapters; ordering, cancellation and late-result admission belong to the coordinator. */
internal interface AppAccountSessionActivation {
    val activeAccount: String?

    /** Restores sign-in and prepares local data without publishing a different active account. */
    suspend fun prepare(
        request: AccountSessionActivationRequest,
        generation: Long,
        switchingAccounts: Boolean,
    ): PreparedAccountSessionActivation?

    /** Rejects deleted/signed-out identities and replacement runtimes at the publication boundary. */
    fun canPublish(
        label: String,
        runtime: Int,
    ): Boolean

    /** Removes old platform share targets before the target account becomes visible. */
    suspend fun hidePreviousAccountShortcuts()

    /** Publishes seeds, preferences and the active account in one non-suspending state transaction. */
    fun publish(
        label: String,
        generation: Long,
        switchingAccounts: Boolean,
        prepared: PreparedAccountSessionActivation,
    )

    /** Warms the current account's self profile without delaying its local-ready callback. */
    suspend fun warmAccountProfile(label: String)

    /** Applies the existing native privacy configuration after local-ready. */
    suspend fun configurePrivacy()

    /** Reads current account notification settings after local-ready. */
    suspend fun refreshNotificationSettings()

    /** Reconciles existing platform delivery services only for a still-current activation. */
    suspend fun reconcileNotificationDelivery(includeBackgroundAccountModes: Boolean)

    /** Supplies current foreground state at service admission. */
    fun isInForeground(): Boolean
}

/** Captures controller handles before their suspending drain so recomposition cannot replace them. */
internal data class AccountSessionTeardown(
    val restartNotifications: Boolean,
    val invalidateActiveSession: () -> Unit,
    val closeControllers: suspend () -> Unit,
)

/**
 * Owns Android account-session ordering. Bootstrap attempts dispatch through [stateDispatcher].
 * Activation, identity publication and teardown inherit the facade caller's state
 * confinement and cancellation context, including its continuation dispatcher. Native startup, one-shot
 * projection handoff and listener cleanup reuse their shipped owners without competing runtime lifetimes.
 */
internal class AppAccountSessionCoordinator(
    private val mutationScope: CoroutineScope,
    private val stateDispatcher: CoroutineDispatcher,
    private val listenerTeardown: () -> AppRuntimeListenerTeardownOwner,
    private val activation: AppAccountSessionActivation,
) {
    private val lifetime = Mutex()

    /** Serializes native lifetime mutations; callers use owned helpers instead of recursive acquisition. */
    suspend fun <T> withLifetime(block: suspend () -> T): T = lifetime.withLock { block() }

    val bootstrapAttempts = BootstrapAttemptCoordinator()

    /** One selection receipt authority, also consumed by the facade's native projection adapters. */
    val handoff = AccountSwitchLocalSnapshotHandoff()

    /** UI cancellation only abandons the wait; the existing mutation scope retains the single bootstrap. */
    suspend fun bootstrapAttempt(start: suspend () -> Unit): Deferred<Unit> =
        withContext(stateDispatcher) {
            bootstrapAttempts.currentOrStart { mutationScope.async { start() } }
        }

    /** Declare intent before waiting so later explicit selections can supersede native creation publication. */
    suspend fun publishCreatedIdentity(
        label: String,
        publish: () -> Unit,
    ) {
        val generation = handoff.beginRequest(label)
        try {
            withLifetime {
                if (!handoff.isCurrent(generation)) {
                    throw kotlinx.coroutines.CancellationException("Identity publication superseded")
                }
                publish()
            }
        } finally {
            handoff.finishRequest(generation)
        }
    }

    /** Retiring selections is part of admitted cleanup, not proof that native sign-out completed. */
    fun retireSelectionsForSignOut(): Long = handoff.beginRequest()

    /** Startup deactivation cannot publish after a later explicit selection wins its lifetime. */
    suspend fun publishSignedOutIdentity(publish: () -> Unit): Boolean {
        val generation = handoff.beginRequest()
        return try {
            withLifetime {
                if (!handoff.isCurrent(generation)) {
                    false
                } else {
                    publish()
                    true
                }
            }
        } finally {
            handoff.finishRequest(generation)
        }
    }

    /** Imported-account UI completion must still belong to the activation after its extra settings read. */
    suspend fun finishImportedIdentity(
        receipt: AccountSessionActivationReceipt,
        refreshSettings: suspend () -> Unit,
        publishReady: () -> Unit,
        warmProfile: suspend () -> Unit,
    ) {
        val isCurrent = {
            handoff.isCurrent(receipt.generation) &&
                activation.activeAccount == receipt.label &&
                activation.canPublish(receipt.label, receipt.runtimeGeneration)
        }
        if (isCurrent()) {
            refreshSettings()
            if (isCurrent()) {
                publishReady()
                warmProfile()
            }
        }
    }

    /** Publishes a latest-wins selection, then admits every deferred refresh against the same runtime. */
    suspend fun activate(
        request: AccountSessionActivationRequest,
        awaitPostActivationWork: suspend () -> Unit = {},
        onActivated: () -> Unit = {},
        beforePrepare: suspend (Long) -> Boolean = { true },
        expectedGeneration: Long? = null,
    ): AccountSessionActivationReceipt? {
        val generation =
            if (expectedGeneration == null) {
                handoff.beginRequest(request.label)
            } else {
                handoff.beginRequestIfCurrent(expectedGeneration, request.label) ?: return null
            }
        return try {
            // Routing may close/join setup commands; keep its original request identity outside the gate.
            if (!beforePrepare(generation)) {
                null
            } else {
                activateCurrentRequest(request, generation, awaitPostActivationWork, onActivated)
            }
        } finally {
            handoff.finishRequest(generation)
        }
    }

    /** A newer explicit request wins without being invalidated by automatic logout succession. */
    suspend fun activateAfterSignOutIfCurrent(
        signOutGeneration: Long,
        request: AccountSessionActivationRequest,
        beforePrepare: suspend (Long) -> Boolean = { true },
        onActivated: () -> Unit = {},
        ownerIsCurrent: () -> Boolean,
    ): AccountSessionActivationReceipt? {
        val generation =
            withLifetime {
                if (activation.activeAccount != null || !ownerIsCurrent()) return@withLifetime null
                handoff.beginRequestIfCurrent(signOutGeneration, request.label)
            } ?: return null
        return try {
            if (!beforePrepare(generation)) return null
            val committed =
                commitCurrentRequest(
                    request.copy(shouldActivate = {
                        ownerIsCurrent() && request.shouldActivate()
                    }),
                    generation,
                    {
                        if (
                            handoff.isCurrent(generation) &&
                            activation.activeAccount == request.label &&
                            ownerIsCurrent()
                        ) {
                            onActivated()
                        }
                    },
                ) ?: return null
            AccountSessionActivationReceipt(request.label, generation, committed.first.runtimeGeneration)
        } finally {
            handoff.finishRequest(generation)
        }
    }

    /** Rechecks after shortcut I/O and keeps publication atomic before the caller's frame barrier. */
    @Suppress("ReturnCount") // Preparation failure and each post-suspension fence reject without publication.
    private suspend fun activateCurrentRequest(
        request: AccountSessionActivationRequest,
        generation: Long,
        awaitPostActivationWork: suspend () -> Unit,
        onActivated: () -> Unit,
    ): AccountSessionActivationReceipt? {
        val committed = commitCurrentRequest(request, generation, onActivated) ?: return null
        val (prepared, switching) = committed
        awaitPostActivationWork()
        refreshActivatedAccount(request, generation, prepared.runtimeGeneration, switching)
        return AccountSessionActivationReceipt(request.label, generation, prepared.runtimeGeneration)
    }

    /** Owns only native local preparation and synchronous publication, without optional enrichment. */
    private suspend fun commitCurrentRequest(
        request: AccountSessionActivationRequest,
        generation: Long,
        onActivated: () -> Unit,
    ): Pair<PreparedAccountSessionActivation, Boolean>? =
        withLifetime {
            if (!request.shouldActivate() || !handoff.isCurrent(generation)) return@withLifetime null
            val switching = request.label != activation.activeAccount
            val prepared = activation.prepare(request, generation, switching) ?: return@withLifetime null
            val allowed = {
                request.shouldActivate() &&
                    handoff.isCurrent(generation) &&
                    activation.canPublish(request.label, prepared.runtimeGeneration)
            }
            if (!allowed()) return@withLifetime null
            if (switching) activation.hidePreviousAccountShortcuts()
            if (!allowed()) return@withLifetime null
            activation.publish(request.label, generation, switching, prepared)
            // The synchronous local-ready projection belongs to this same accepted publication.
            onActivated()
            prepared to switching
        }

    /** Optional successor enrichment stays in the original cancellable caller after owned bookkeeping finishes. */
    suspend fun refreshAfterSignOut(receipt: AccountSessionActivationReceipt) {
        refreshActivatedAccount(
            AccountSessionActivationRequest(receipt.label),
            receipt.generation,
            receipt.runtimeGeneration,
            switching = true,
        )
    }

    /** Each suspension can replace the account/runtime, so later effects must revalidate ownership. */
    @Suppress("ReturnCount") // Every suspending adapter is a separate stale-owner admission boundary.
    private suspend fun refreshActivatedAccount(
        request: AccountSessionActivationRequest,
        generation: Long,
        runtimeGeneration: Int,
        switching: Boolean,
    ) {
        val isCurrent = {
            activation.canPublish(request.label, runtimeGeneration) &&
                activation.activeAccount == request.label &&
                handoff.isCurrent(generation)
        }
        if (!isCurrent()) return
        activation.warmAccountProfile(request.label)
        if (!isCurrent()) return
        activation.configurePrivacy()
        if (!isCurrent()) return
        activation.refreshNotificationSettings()
        if (!isCurrent()) return
        val startup = request.preloadPolicy == AccountSwitchPreloadPolicy.STARTUP_RESTORATION
        if (startup && !activation.isInForeground()) return
        activation.reconcileNotificationDelivery(includeBackgroundAccountModes = switching && !startup)
    }

    /** Fences the runtime, drains captured controllers, then joins the existing listener owner. */
    suspend fun prepareForAccountTeardown(capture: suspend () -> AccountSessionTeardown): Boolean {
        // Invalidate suspended selections before setup cancellation can suspend inside capture.
        handoff.beginRequest()
        val teardown = capture()
        teardown.invalidateActiveSession()
        teardown.closeControllers()
        stopListenersForAccountTeardown()
        return teardown.restartNotifications
    }

    /** Delegates to the shipped owner, including native subscription close and recovery-job joins. */
    suspend fun stopListenersForAccountTeardown() = listenerTeardown().stopForAccountTeardown()
}
