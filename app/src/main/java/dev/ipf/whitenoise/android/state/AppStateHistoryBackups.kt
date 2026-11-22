package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.BackupHistoryAvailabilityFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val account = activeAccountRef?.takeIf { historyBackupsEligible(it) }
    // One capture binds the runtime and its generation together; isCurrent checks both.
    val owner = captureHostPerformanceRuntimeOwner()
    if (account == null || owner == null) return null
    return HistoryBackupsController(
        marmot = owner.runtime.marmot,
        accountRef = account,
        scope = scope,
        isCurrent = {
            activeAccountRef == account && ownsHostPerformanceRuntimeOwner(owner) && historyBackupsEligible(account)
        },
        onBackupsOn = { HistoryBackupWorker.schedule(appContext) },
        cleanupScope = mutationsScope,
    )
}

/**
 * MDK's private words-again hints for one chat this account is in: a restore found backed-up history for it before
 * this device joined, so entering the same recovery words again can bring that history in. Empty when there is
 * nothing to say, the account is not eligible, or the read fails; a hint is advice, never proof of content.
 */
internal suspend fun WhiteNoiseAppState.historyBackupHints(
    accountRef: String?,
    groupIdHex: String,
): List<BackupHistoryAvailabilityFfi> {
    val account = accountRef?.takeIf { historyBackupsEligible(it) }
    val marmot = account?.let { captureHostPerformanceRuntimeOwner()?.runtime?.marmot }
    if (account == null || marmot == null) return emptyList()
    return try {
        withContext(Dispatchers.IO) { marmot.backupHistoryAvailability(account, groupIdHex) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Starts the backup schedule when a signed-in account has backups on. MDK can make this device the writer without the
 * screen (taking over from a writer that left), and the process can die between accepted words and scheduling, so a
 * newly published runtime checks once; the schedule keeps an existing cadence.
 */
internal fun WhiteNoiseAppState.scheduleHistoryBackupsIfOn(marmot: MarmotInterface) {
    mutationsScope.launch(Dispatchers.IO) {
        val accounts =
            try {
                marmot.listAccounts().filter { it.isSignedInSigningAccount() }.map { it.label }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
        if (anyHistoryBackupsOn(marmot, accounts)) HistoryBackupWorker.schedule(appContext)
    }
}
