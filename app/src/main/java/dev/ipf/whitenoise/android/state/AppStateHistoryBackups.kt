package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.BackupHistoryAvailabilityFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val runtime = captureHostPerformanceRuntimeOwner()?.runtime
    if (account == null || runtime == null) return null
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
