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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    private var pending = emptySet<String>()

    /** Rebuilds eligibility exclusively from MDK's persisted setup snapshots. */
    suspend fun pendingAccounts(accounts: List<AccountSummaryFfi>): Set<String> =
        app.marmotIo {
            accounts.filter { onboardingSnapshot(it.label)?.requiresSetup() == true }.map { it.label }.toSet()
        }

    /** Publishes eligibility together with the account list after its stale-read guard accepts both. */
    fun acceptAccounts(pendingAccounts: Set<String>) {
        pending = pendingAccounts
    }

    /** Pending accounts stay visible in the picker but are excluded from normal background work. */
    fun eligible(account: AccountSummaryFfi): Boolean = account.label !in pending

    /** Imports only local identity material and then mounts the saved preflight route. */
    suspend fun begin(nsec: String): OnboardingSnapshotFfi {
        val snapshot = app.marmotIo { beginOnboarding(nsec, setupOptions()) }
        open(snapshot)
        return snapshot
    }

    /** Gates all account-selection paths before legacy reactivation or chat preload. */
    suspend fun routeIfPending(account: String): Boolean {
        val snapshot = app.marmotIo { onboardingSnapshot(account) } ?: return false
        val required = snapshot.requiresSetup()
        if (required) open(snapshot)
        return required
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
