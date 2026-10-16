package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationCatchUpWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
    fun canPublish(label: String, runtime: Int): Boolean

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
 * Owns Android account-session ordering. Bootstrap dispatches through [stateDispatcher]; catch-up retains
 * its recovery scope. Activation, identity publication and teardown inherit the facade caller's state
 * confinement and cancellation context, including its continuation dispatcher. Native startup, one-shot
 * projection handoff and listener cleanup reuse their shipped owners without competing runtime lifetimes.
 */
internal class AppAccountSessionCoordinator(
    private val mutationScope: CoroutineScope,
    recoveryScope: CoroutineScope,
    private val stateDispatcher: CoroutineDispatcher,
    catchUpWindow: NotificationCatchUpWindow?,
    private val listenerTeardown: () -> AppRuntimeListenerTeardownOwner,
    private val activation: AppAccountSessionActivation,
) {
    val bootstrapAttempts = BootstrapAttemptCoordinator()
    val bootstrapRuntime = BootstrapRuntimeCoordinator<AppMarmotRuntime>()
    val handoff = AccountSwitchLocalSnapshotHandoff()
    val catchUp = AccountCatchUpCoordinator(recoveryScope, catchUpWindow)

    @Volatile
    var bootstrapCompleted = false

    /** UI cancellation only abandons the wait; the existing mutation scope retains the single bootstrap. */
    suspend fun bootstrapAttempt(start: suspend () -> Unit): Deferred<Unit> =
        withContext(stateDispatcher) {
            bootstrapAttempts.currentOrStart { mutationScope.async { start() } }
        }

    /** A synchronous created-identity publication supersedes any suspended selection of an older account. */
    fun publishCreatedIdentity(label: String, publish: () -> Unit) {
        val generation = handoff.beginRequest(label)
        try {
            publish()
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
            handoff.isCurrent(receipt.generation) && activation.activeAccount == receipt.label &&
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
    ): AccountSessionActivationReceipt? {
        val generation = handoff.beginRequest(request.label)
        return try {
            activateCurrentRequest(request, generation, awaitPostActivationWork, onActivated)
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
        val switching = request.label != activation.activeAccount
        val prepared = activation.prepare(request, generation, switching) ?: return null
        val allowed = {
            request.shouldActivate() && handoff.isCurrent(generation) &&
                activation.canPublish(request.label, prepared.runtimeGeneration)
        }
        if (!allowed()) return null
        if (switching) activation.hidePreviousAccountShortcuts()
        if (!allowed()) return null
        activation.publish(request.label, generation, switching, prepared)
        onActivated()
        awaitPostActivationWork()
        refreshActivatedAccount(request, generation, prepared.runtimeGeneration, switching)
        return AccountSessionActivationReceipt(request.label, generation, prepared.runtimeGeneration)
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
                activation.activeAccount == request.label && handoff.isCurrent(generation)
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
