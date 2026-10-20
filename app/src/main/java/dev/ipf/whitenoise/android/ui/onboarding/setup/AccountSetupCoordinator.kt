package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingOptionsFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.appStateDebug
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ACCOUNT_LOG_PREFIX_LENGTH = 8

/** Owns the visible setup route independently of the active Chats account. */
internal class AccountSetupCoordinator(
    private val app: WhiteNoiseAppState,
    private val scope: CoroutineScope,
    private val onPhaseChange: (AppPhase) -> Unit,
    private val awaitActivationReadiness: suspend () -> Unit,
    private val reconnectSigner: suspend (MarmotInterface, String, () -> Boolean) -> Unit,
) {
    var controller by mutableStateOf<AccountSetupController?>(null)
        private set
    private var generation = 0L
    private var pending by mutableStateOf(emptySet<String>())
    private var recoveryRequired by mutableStateOf(emptySet<String>())
    private val commands = Commands()

    /** Account-scoped setup eligibility returned from one runtime generation. */
    internal data class AccountsState(
        val pending: Set<String>,
        val recoveryRequired: Set<String>,
    )

    /** Rebuilds eligibility exclusively from MDK's persisted setup state. */
    suspend fun accountsState(accounts: List<AccountSummaryFfi>): AccountsState =
        app.marmotIo {
            val recovery = mutableSetOf<String>()
            val pending = mutableSetOf<String>()
            accounts.forEach { account ->
                val accountRef = account.label
                val recoveryRead = runCatchingCancellable { onboardingRecoveryRequired(accountRef) }
                if (recoveryRead.isFailure) {
                    pending += accountRef
                    appStateDebug {
                        "onboarding recovery-state read failed for ${accountRef.take(ACCOUNT_LOG_PREFIX_LENGTH)}"
                    }
                    return@forEach
                }
                if (recoveryRead.getOrThrow()) {
                    recovery += accountRef
                    return@forEach
                }
                val snapshotRead = runCatchingCancellable { onboardingSnapshot(accountRef) }
                if (snapshotRead.isFailure) {
                    pending += accountRef
                    appStateDebug {
                        "onboarding checkpoint read failed for ${accountRef.take(ACCOUNT_LOG_PREFIX_LENGTH)}"
                    }
                    return@forEach
                }
                if (snapshotRead.getOrNull()?.requiresSetup() == true) pending += accountRef
            }
            AccountsState(pending = pending, recoveryRequired = recovery)
        }

    /** Publishes eligibility together with the account list after its stale-read guard accepts both. */
    fun acceptAccounts(state: AccountsState) {
        pending = state.pending
        recoveryRequired = state.recoveryRequired
    }

    /** Whether MDK requires explicit destructive recovery before this account can resume setup. */
    fun needsRecovery(account: String): Boolean = account in recoveryRequired

    /** Pending accounts stay visible in the picker but are excluded from normal background work. */
    fun eligible(account: AccountSummaryFfi): Boolean = account.label !in pending && account.label !in recoveryRequired

    /** Imports only local identity material and then mounts the saved preflight route. */
    suspend fun begin(
        nsec: String,
        mayBegin: () -> Boolean = { true },
    ): OnboardingSnapshotFfi {
        val owner = app.setupRuntimeOwner()
        val snapshot =
            app.withAccountLifetime {
                app.requireSetupOwner(owner)
                if (!mayBegin()) throw CancellationException("Setup action was replaced")
                withContext(Dispatchers.IO) { owner.runtime.beginOnboarding(nsec, setupOptions()) }
            }
        commands.open(snapshot, owner, mayBegin)
        return snapshot
    }

    /** Gates all account-selection paths before legacy reactivation or chat preload. */
    suspend fun routeIfPending(
        account: String,
        mayRoute: () -> Boolean = { true },
    ): Boolean {
        if (!mayRoute()) throw CancellationException("Setup route was superseded")
        if (needsRecovery(account)) return true
        val owner = app.setupRuntimeOwner()

        fun requireRoute() {
            app.requireSetupOwner(owner)
            if (!mayRoute()) throw CancellationException("Setup route was superseded")
        }
        val recoveryRead =
            withContext(Dispatchers.IO) {
                runCatchingCancellable { owner.runtime.onboardingRecoveryRequired(account) }
            }
        requireRoute()
        return when {
            recoveryRead.isFailure -> {
                pending = pending + account
                appStateDebug {
                    "onboarding recovery-state read failed for ${account.take(ACCOUNT_LOG_PREFIX_LENGTH)}"
                }
                true
            }

            recoveryRead.getOrThrow() -> {
                pending = pending - account
                recoveryRequired = recoveryRequired + account
                true
            }

            else -> {
                recoveryRequired = recoveryRequired - account
                val snapshotRead =
                    withContext(Dispatchers.IO) {
                        runCatchingCancellable { owner.runtime.onboardingSnapshot(account) }
                    }
                requireRoute()
                val snapshot = snapshotRead.getOrNull()
                when {
                    snapshotRead.isFailure -> {
                        pending = pending + account
                        appStateDebug {
                            "onboarding checkpoint read failed for ${account.take(ACCOUNT_LOG_PREFIX_LENGTH)}"
                        }
                        true
                    }

                    snapshot?.requiresSetup() == true -> {
                        commands.open(snapshot, owner, mayRoute)
                        true
                    }

                    else -> {
                        pending = pending - account
                        false
                    }
                }
            }
        }
    }

    /** Replaces an unreadable checkpoint only after the onboarding UI's explicit acknowledgement. */
    suspend fun recover(account: String): Boolean {
        val owner = app.setupRuntimeOwner()
        val required = withContext(Dispatchers.IO) { owner.runtime.onboardingRecoveryRequired(account) }
        app.requireSetupOwner(owner)
        if (!required) return false
        val token = commands.drain()
        app.withAccountLifetime {
            app.requireSetupOwner(owner)
            if (generation != token) throw CancellationException("Setup route was replaced")
            withContext(Dispatchers.IO) {
                owner.runtime.recoverOnboarding(account, acknowledgeLatestOnlyEvidence = true)
            }
        }
        return true
    }

    /** Mounts a controller tied to the current runtime and never changes the active account. */
    suspend fun open(snapshot: OnboardingSnapshotFfi) = commands.open(snapshot, app.setupRuntimeOwner())

    /** External signer setup keeps its producer receipt and original selection across controller draining. */
    internal suspend fun open(
        snapshot: OnboardingSnapshotFfi,
        runtime: MarmotInterface,
        epoch: Int,
        mayMount: () -> Boolean,
    ) = commands.open(snapshot, SetupRuntimeOwner(runtime, epoch), mayMount)

    /** Leaves publications intact and returns to the previous usable account or sign-in screen. */
    suspend fun later() {
        close()
        app.refreshAccounts()
        onPhaseChange(if (app.activeAccountRef == null) AppPhase.Onboarding else AppPhase.Ready)
    }

    /** Invalidates callbacks before draining the old native reader and command. */
    suspend fun close() {
        commands.drain()
    }

    private inner class Commands {
        suspend fun open(
            snapshot: OnboardingSnapshotFfi,
            owner: SetupRuntimeOwner,
            mayMount: () -> Boolean = { true },
        ) {
            app.requireSetupOwner(owner)
            requireSetupAction(mayMount(), "Setup action was replaced")
            val token = drain()
            app.withAccountLifetime {
                app.requireSetupOwner(owner)
                val actionCurrent = mayMount()
                requireSetupAction(actionCurrent, "Setup action was replaced")
                requireSetupAction(generation == token, "Setup route was replaced")
                val runtimeGeneration = owner.epoch
                val runtime = owner.runtime
                val account = snapshot.accountIdHex
                pending = pending + account
                val isMountCurrent = {
                    generation == token &&
                        app.runtimeGeneration == runtimeGeneration &&
                        app.marmot() === runtime &&
                        mayMount()
                }
                val client =
                    MarmotAccountSetupClient(
                        runtime,
                        account,
                        withLifetime = { command ->
                            app.withAccountLifetime {
                                if (!isMountCurrent()) throw CancellationException("Setup mount was replaced")
                                command()
                            }
                        },
                        withOwnedPreviewCleanup = { command ->
                            app.withAccountLifetime {
                                // Controller close drains this owned preview before native lifetime can end.
                                app.requireSetupOwner(owner)
                                command()
                            }
                        },
                    ) { reconnectSigner(runtime, account, isMountCurrent) }
                controller =
                    AccountSetupController(
                        account,
                        client,
                        scope,
                        isCurrent = isMountCurrent,
                        onReady = { finish(token, owner, client, account, isMountCurrent) },
                        onCancelled = { app.launchMutation { if (generation == token) later() } },
                    )
                if (app.phase == AppPhase.Bootstrapping) onPhaseChange(AppPhase.Onboarding)
            }
            controller?.reconnect()
        }

        suspend fun drain(): Long {
            val token = ++generation
            val previous = controller
            controller = null
            previous?.close()
            return token
        }

        /** Activates only a freshly certified account and leaves the route available if activation fails. */
        suspend fun finish(
            token: Long,
            owner: SetupRuntimeOwner,
            client: AccountSetupClient,
            account: String,
            isMountCurrent: () -> Boolean,
        ) {
            val selection = app.accountSession.handoff.capture()
            if (!setupReadyForActivation(client, isMountCurrent)) return
            app.refreshAccounts()
            if (!isMountCurrent()) return
            // Resumed setup may have diverted bootstrap before its receiver/privacy barrier.
            awaitActivationReadiness()
            if (isMountCurrent()) {
                val activated =
                    app.setActiveAccount(
                        account,
                        shouldActivate = {
                            generation == token &&
                                app.runtimeGeneration == owner.epoch &&
                                app.marmot() === owner.runtime
                        },
                        expectedSelection = selection,
                        onActivated = { onPhaseChange(AppPhase.Ready) },
                    )
                if (activated) scope.launch { if (generation == token) close() }
            }
        }
    }
}

/** A cancelled or unfinished checkpoint can never certify chat readiness. */
internal fun OnboardingSnapshotFfi.requiresSetup(): Boolean = !ready || cancellationPending

/** Discover existing Nostr lists beyond our messaging relays before offering additive defaults. */
internal fun setupOptions() =
    OnboardingOptionsFfi(
        defaultRelays = MarmotClient.accountRelays,
        discoveryRelays = MarmotClient.discoveryRelays,
        inboxRelays = MarmotClient.bootstrapRelays,
    )

private data class SetupRuntimeOwner(
    val runtime: MarmotInterface,
    val epoch: Int,
)

private fun WhiteNoiseAppState.setupRuntimeOwner() = SetupRuntimeOwner(marmot(), runtimeGeneration)

private fun WhiteNoiseAppState.requireSetupOwner(owner: SetupRuntimeOwner) {
    if (runtimeGeneration != owner.epoch || marmot() !== owner.runtime) {
        throw CancellationException("Setup runtime was replaced")
    }
}

private suspend fun setupReadyForActivation(
    client: AccountSetupClient,
    isMountCurrent: () -> Boolean,
): Boolean =
    if (!isMountCurrent()) {
        false
    } else {
        client.snapshot()?.let { snapshot ->
            isMountCurrent() && snapshot.ready && !snapshot.cancellationPending
        } ?: false
    }

private fun requireSetupAction(
    current: Boolean,
    message: String,
) {
    if (!current) throw CancellationException(message)
}
