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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val ACCOUNT_LOG_PREFIX_LENGTH = 8

/** Owns the visible setup route independently of the active Chats account. */
internal class AccountSetupCoordinator(
    private val app: WhiteNoiseAppState,
    private val scope: CoroutineScope,
    private val onPhaseChange: (AppPhase) -> Unit,
    private val awaitActivationReadiness: suspend () -> Unit,
    private val reconnectSigner: suspend (MarmotInterface, String) -> Unit,
) {
    var controller by mutableStateOf<AccountSetupController?>(null)
        private set
    private var generation = 0L
    private var pending by mutableStateOf(emptySet<String>())
    private var recoveryRequired by mutableStateOf(emptySet<String>())

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
    suspend fun begin(nsec: String): OnboardingSnapshotFfi {
        val snapshot = app.marmotIo { beginOnboarding(nsec, setupOptions()) }
        open(snapshot)
        return snapshot
    }

    /** Gates all account-selection paths before legacy reactivation or chat preload. */
    suspend fun routeIfPending(account: String): Boolean {
        if (needsRecovery(account)) return true
        val recoveryRead = app.marmotIo { runCatchingCancellable { onboardingRecoveryRequired(account) } }
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
                val snapshotRead = app.marmotIo { runCatchingCancellable { onboardingSnapshot(account) } }
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
                        open(snapshot)
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
        if (!app.marmotIo { onboardingRecoveryRequired(account) }) return false
        close()
        app.marmotIo { recoverOnboarding(account, acknowledgeLatestOnlyEvidence = true) }
        return true
    }

    /** Mounts a controller tied to the current runtime and never changes the active account. */
    suspend fun open(snapshot: OnboardingSnapshotFfi) {
        close()
        val token = generation
        val runtimeGeneration = app.runtimeGeneration
        val runtime = app.marmot()
        val account = snapshot.accountIdHex
        pending = pending + account
        val client = MarmotAccountSetupClient(runtime, account) { reconnectSigner(runtime, account) }
        controller =
            AccountSetupController(
                account,
                client,
                scope,
                isCurrent = { generation == token && app.runtimeGeneration == runtimeGeneration },
                onReady = { finish(token, runtimeGeneration, client, account) },
                onCancelled = { app.launchMutation { if (generation == token) later() } },
            )
        if (app.phase == AppPhase.Bootstrapping) onPhaseChange(AppPhase.Onboarding)
        controller?.reconnect()
    }

    /** Leaves publications intact and returns to the previous usable account or sign-in screen. */
    suspend fun later() {
        close()
        app.refreshAccounts()
        onPhaseChange(if (app.activeAccountRef == null) AppPhase.Onboarding else AppPhase.Ready)
    }

    /** Invalidates callbacks before draining the old native reader and command. */
    suspend fun close() {
        generation++
        val previous = controller
        controller = null
        previous?.close()
    }

    /** Activates only a freshly certified account and leaves the route available if activation fails. */
    private suspend fun finish(
        token: Long,
        runtimeGeneration: Int,
        client: AccountSetupClient,
        account: String,
    ) {
        if (generation != token || app.runtimeGeneration != runtimeGeneration) return
        val snapshot = client.snapshot() ?: return
        if (snapshot.ready && !snapshot.cancellationPending && generation == token) {
            app.refreshAccounts()
            // Resumed setup may have diverted bootstrap before its receiver/privacy barrier.
            awaitActivationReadiness()
            if (generation == token && app.runtimeGeneration == runtimeGeneration) {
                val activated =
                    app.setActiveAccount(
                        account,
                        shouldActivate = { generation == token && app.runtimeGeneration == runtimeGeneration },
                        onActivated = { onPhaseChange(AppPhase.Ready) },
                    )
                if (activated) scope.launch { if (generation == token) close() }
            }
        }
    }
}

/** A cancelled or unfinished checkpoint can never certify chat readiness. */
internal fun OnboardingSnapshotFfi.requiresSetup(): Boolean = !ready || cancellationPending

/** Confirmed-missing lists use these native defaults; existing relay lists still require review. */
internal fun setupOptions() = OnboardingOptionsFfi(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)
