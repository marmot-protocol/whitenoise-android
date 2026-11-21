package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope

/** Backups belong to a loaded, signed-in account that is not being signed out or wiped. */
internal fun WhiteNoiseAppState.historyBackupsEligible(account: String? = activeAccountRef): Boolean =
    !signOutInProgress &&
        !wipeInProgress &&
        accounts.any { it.label == account && it.isSignedInSigningAccount() }

/**
 * A controller for the active account's history backups, bound to the current runtime. It retires itself, dropping
 * late answers, once the account, the runtime or eligibility changes. Null when no runtime or eligible account exists.
 */
internal fun WhiteNoiseAppState.historyBackupsController(scope: CoroutineScope): HistoryBackupsController? {
    val account = activeAccountRef ?: return null
    val runtime = captureHostPerformanceRuntimeOwner()?.runtime ?: return null
    if (!historyBackupsEligible(account)) return null
    val generation = runtimeGeneration
    return HistoryBackupsController(
        marmot = runtime.marmot,
        accountRef = account,
        scope = scope,
        isCurrent = {
            activeAccountRef == account && runtimeGeneration == generation && historyBackupsEligible(account)
        },
        onWriting = { HistoryBackupWorker.schedule(appContext) },
        cleanupScope = mutationsScope,
    )
}
