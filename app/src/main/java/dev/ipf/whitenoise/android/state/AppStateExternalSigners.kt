package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.Nip46ErrorFfi
import dev.ipf.marmotkit.Nip46SessionFfi
import dev.ipf.whitenoise.android.core.MarmotClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-install the NIP-55 signer callback for every external-signing account
 * after an engine (re)start. MDK persists only the account pubkey for these
 * accounts, so the `ExternalAccountSignerFfi` must be re-registered before
 * any signing happens. Best-effort per account: a failure (e.g. Amber
 * uninstalled) is logged and leaves the account intact rather than aborting
 * bootstrap; that account's signing will later surface a typed error.
 *
 * MDK restores remote-signer (NIP-46) sessions itself during start. Those accounts are claimed first and never
 * get an Amber callback, because registering one would replace the restored remote signer.
 */
internal suspend fun WhiteNoiseAppState.reregisterExternalSigners() {
    val remote = restoreRemoteSignerAccounts()
    accounts.filter { it.externalSigning && it.accountIdHex !in remote }.forEach { account ->
        runCatchingCancellable {
            marmotIo { registerExternalSigner(account.label, amberSigner.buildSigner(account.accountIdHex)) }
        }.onFailure {
            val label = account.label.take(8)
            appStateDebug(it) { "external signer re-register failed for $label: ${it.readableMessage()}" }
        }
    }
}

/**
 * Reattaches the signer an interrupted external setup needs. A remote-signer account reconnects its MDK session;
 * any other external account asks Amber for its key again.
 */
internal suspend fun WhiteNoiseAppState.reconnectSetupSigner(
    runtime: MarmotInterface,
    account: String,
    isMountCurrent: () -> Boolean,
) {
    fun requireMount() {
        if (!isMountCurrent() || marmot() !== runtime) throw CancellationException("Setup mount was replaced")
    }
    requireMount()
    if (remoteSigners.session(runtime, account) == null && remoteSigners.status(account) != null) {
        resumeRemoteSigner(runtime, accountRefFor(account), account)
    }
    requireMount()
    val session = remoteSigners.session(runtime, account)
    // An unresolved remote-signer account never falls back to Amber, which would replace its signer.
    check(session != null || remoteSigners.status(account) == null) { "Remote signer session is unavailable" }
    if (session != null) {
        withAccountLifetime {
            requireMount()
            session.connect()
        }
    } else {
        val reported = withContext(Dispatchers.IO) { amberSigner.requestPublicKey() }
        withAccountLifetime {
            requireMount()
            val canonical = withContext(Dispatchers.IO) { runtime.accountIdHex(reported) }
            check(canonical == account) { "signer account does not match setup" }
            withContext(Dispatchers.IO) {
                runtime.registerExternalSigner(account, amberSigner.buildSigner(account))
            }
        }
    }
}

/**
 * Claims every external account MDK knows as a remote-signer account and returns their ids; none of them may get an
 * Amber callback. MDK's answer that an account has no NIP-46 credentials (`Unavailable`) leaves it an Amber account
 * unless it signed in with a remote signer here, whose pairing was then ended. Any other resume failure keeps the
 * account unresolved, shown as unreachable with a retry.
 */
private suspend fun WhiteNoiseAppState.restoreRemoteSignerAccounts(): Set<String> {
    val runtime = marmotOrNull() ?: return emptySet()
    remoteSigners.retainRuntime(runtime)
    val remembered = rememberedRemoteSignerAccounts()
    val claimed = HashSet<String>()
    accounts.filter { it.externalSigning }.forEach { account ->
        when (val outcome = resumeRemoteSigner(runtime, account.label, account.accountIdHex)) {
            // Disconnecting or signing out erases MDK's pairing; such an account still waits to be paired again.
            RemoteSignerResume.Absent ->
                if (account.accountIdHex in remembered && marmotOrNull() === runtime) {
                    claimed += account.accountIdHex
                    remoteSigners.markWithoutSession(runtime, account.accountIdHex, RemoteSignerHealth.PairingEnded)
                }
            RemoteSignerResume.Restored -> {
                claimed += account.accountIdHex
                rememberRemoteSignerAccount(account.accountIdHex, remote = true)
            }
            is RemoteSignerResume.Unresolved -> {
                claimed += account.accountIdHex
                val label = account.label.take(8)
                appStateDebug { "remote signer restore failed for $label: ${outcome.error.readableMessage()}" }
            }
        }
    }
    if (remoteSigners.hasSessions()) watchRemoteSigners()
    return claimed
}

/** What resuming one account's MDK-owned NIP-46 session found. */
internal sealed interface RemoteSignerResume {
    data object Restored : RemoteSignerResume

    data object Absent : RemoteSignerResume

    class Unresolved(
        val error: Throwable,
    ) : RemoteSignerResume
}

/** Resumes [accountIdHex]'s session on [runtime] and records the outcome for display. */
internal suspend fun WhiteNoiseAppState.resumeRemoteSigner(
    runtime: MarmotInterface,
    accountRef: String,
    accountIdHex: String,
): RemoteSignerResume =
    runCatchingCancellable { withContext(Dispatchers.IO) { runtime.nip46Resume(accountRef) } }.fold(
        onSuccess = { session ->
            // A runtime replaced while resume ran owns its own handles; this one is stale and is not recorded.
            if (marmotOrNull() === runtime) remoteSigners.adopt(runtime, accountIdHex, session)
            RemoteSignerResume.Restored
        },
        onFailure = { error ->
            if (error is MarmotKitException.Nip46 && error.reason is Nip46ErrorFfi.Unavailable) {
                RemoteSignerResume.Absent
            } else {
                if (marmotOrNull() === runtime) {
                    remoteSigners.markWithoutSession(runtime, accountIdHex, RemoteSignerHealth.Unreachable)
                }
                RemoteSignerResume.Unresolved(error)
            }
        },
    )

/**
 * Creates or reopens the account behind a verified, Ready remote-signer session on [runtime], the runtime that
 * created it, then activates it like any imported identity. MDK persists the session's credentials; Android keeps
 * only the in-memory handle.
 */
internal suspend fun WhiteNoiseAppState.loginWithRemoteSigner(
    session: Nip46SessionFfi,
    runtime: MarmotInterface,
) {
    val epoch = runtimeGeneration
    val selection = accountSession.handoff.capture()
    val summary =
        withAccountLifetime {
            val current = marmot() === runtime && accountSession.handoff.isCurrent(selection)
            check(current) { "Remote signer owner was replaced" }
            withContext(Dispatchers.IO) {
                runtime.nip46Login(session, whiteNoiseNip65Relays, MarmotClient.bootstrapRelays, whiteNoiseInboxRelays)
            }
        }
    rememberRemoteSignerAccount(summary.accountIdHex, remote = true)
    // A runtime replaced while login ran owns its own handles; this session belongs to the old one.
    if (marmotOrNull() === runtime) remoteSigners.adopt(runtime, summary.accountIdHex, session)
    watchRemoteSigners()
    activateImportedIdentity(summary, runtime, epoch, selection)
}

/**
 * Attaches a freshly paired session from [runtime] to existing account [accountIdHex]. The previous pairing is
 * detached first, as MDK requires before a replacement; the account and its history stay. If attaching then fails,
 * the account shows that it needs pairing again instead of offering recovery on the detached session.
 */
internal suspend fun WhiteNoiseAppState.repairRemoteSigner(
    accountIdHex: String,
    session: Nip46SessionFfi,
    runtime: MarmotInterface,
) {
    val account = accounts.firstOrNull { it.accountIdHex == accountIdHex && it.externalSigning }
    checkNotNull(account) { "Remote signer account is gone" }
    withAccountLifetime {
        check(marmot() === runtime) { "Remote signer owner was replaced" }
        withContext(Dispatchers.IO) {
            // A failed removal leaves the old pairing attached, so nothing is marked and the error surfaces.
            runtime.nip46Remove(account.label)
            // The old handle is detached now; until registration commits the account needs pairing again.
            if (marmotOrNull() === runtime) {
                remoteSigners.markWithoutSession(runtime, accountIdHex, RemoteSignerHealth.PairingEnded)
            }
            runtime.nip46Register(account.label, session)
        }
    }
    rememberRemoteSignerAccount(accountIdHex, remote = true)
    if (marmotOrNull() === runtime) remoteSigners.adopt(runtime, accountIdHex, session)
    watchRemoteSigners()
}

/** MDK's account reference (its label) for [accountIdHex]; the hex itself when the account is not listed. */
internal fun WhiteNoiseAppState.accountRefFor(accountIdHex: String): String {
    val account = accounts.firstOrNull { it.accountIdHex == accountIdHex }
    return account?.label ?: accountIdHex
}

private const val REMOTE_SIGNER_ACCOUNTS_KEY = "remote_signer_accounts"

/**
 * Account ids that signed in with a remote signer on this device. MDK erases a pairing when it is disconnected or
 * the account signs out, so this non-secret list keeps such an account from being taken for an Amber account after
 * a restart.
 */
internal fun WhiteNoiseAppState.rememberedRemoteSignerAccounts(): Set<String> {
    val stored = preferences.getStringSet(REMOTE_SIGNER_ACCOUNTS_KEY, null)
    return stored.orEmpty()
}

/** Records whether [accountIdHex] signs with a remote signer; signing in with Amber for it clears the record. */
internal fun WhiteNoiseAppState.rememberRemoteSignerAccount(
    accountIdHex: String,
    remote: Boolean,
) = synchronized(remoteSigners) {
    val current = rememberedRemoteSignerAccounts()
    val next = if (remote) current + accountIdHex else current - accountIdHex
    if (next != current) preferences.edit().putStringSet(REMOTE_SIGNER_ACCOUNTS_KEY, next).apply()
}
