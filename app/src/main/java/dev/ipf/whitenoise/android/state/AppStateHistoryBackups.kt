package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.BackupHistoryAvailabilityFfi
import dev.ipf.marmotkit.BackupRestoreStatusFfi
import dev.ipf.marmotkit.BackupSettingsFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RecoveryWordsFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Captures the active account and runtime for history backups; null without an eligible account or runtime. */
internal fun WhiteNoiseAppState.historyBackupsAccess(): HistoryBackupsAccess? {
    val account = activeAccountRef?.takeIf { signedInAccountEligible(it) }
    // One capture binds the runtime and its generation together; the owner check reads both.
    val owner = captureHostPerformanceRuntimeOwner()
    if (account == null || owner == null) return null
    return NativeHistoryBackupsAccess(account, owner.runtime.marmot, owns = {
        activeAccountRef == account && ownsHostPerformanceRuntimeOwner(owner) && signedInAccountEligible(account)
    })
}

internal class NativeHistoryBackupsAccess(
    private val account: String,
    private val marmot: MarmotInterface,
    private val owns: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HistoryBackupsAccess {
    override fun isCurrent(): Boolean = owns()

    override suspend fun settings(): BackupSettingsFfi = call { backupSettings(account) }

    override suspend fun beginWords(): RecoveryWordsFfi = call { beginRecoveryWords(account) }

    override suspend fun confirmWords(answers: List<String>) = call { confirmRecoveryWords(account, answers) }

    override suspend fun forgetWords() = withContext(dispatcher) { marmot.cancelRecoveryWords(account) }

    override suspend fun turnOff() = call { turnOffBackups(account) }

    override suspend fun beginRestore(words: String): BackupRestoreStatusFfi =
        call { beginBackupRestore(account, words) }

    override suspend fun cancelRestore(operation: String) {
        call { cancelBackupRestore(account, operation) }
    }

    override suspend fun restoreStatus(): BackupRestoreStatusFfi = call { backupRestoreStatus(account) }

    override suspend fun followRestore(onStatus: (BackupRestoreStatusFfi) -> Unit) =
        call {
            // Acquisition, reads and close all enter the binding off the main thread; a cancelled acquisition still
            // returns its subscription so it is closed.
            val watch = withContext(NonCancellable) { watchBackupRestore(account) }
            try {
                watch.snapshot()?.let(onStatus)
                while (isCurrent()) onStatus(watch.next() ?: break)
            } finally {
                watch.close()
            }
        }

    private suspend fun <T> call(block: suspend MarmotInterface.() -> T): T =
        withContext(dispatcher) {
            if (!isCurrent()) throw CancellationException("History backups owner retired")
            marmot.block()
        }
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
    val account = accountRef?.takeIf { signedInAccountEligible(it) } ?: return emptyList()
    return runCatchingCancellable { marmotIo { backupHistoryAvailability(account, groupIdHex) } }
        .getOrDefault(emptyList())
}

/**
 * One background visit (#3655): tells MDK the device's conditions, then joins or tries each eligible account's due
 * backup pass. MDK decides what is due, including a reading device taking over from a quiet writer, and answers
 * NotStarted when backups are off. False when a pass failed or the visit ran out of [budgetMillis], so WorkManager
 * tries again; a pass outliving the budget is detached, not cancelled in MDK, and the next visit joins it.
 */
internal suspend fun WhiteNoiseAppState.runDueHistoryBackups(budgetMillis: Long): Boolean {
    ensureNotificationRuntimeStarted()
    val marmot = retentionSweepRuntimeOrNull()
    // MDK admits no pass until it holds the facts; never run on the closed default.
    if (marmot == null || !HistoryBackupConditions.reportNow(marmot)) return false
    val eligible = accounts.filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }.map { it.label }
    return runDueHistoryBackupPasses(eligible, budgetMillis) { account ->
        withContext(Dispatchers.IO) { marmot.runDueBackupPass(account) }
    }
}

/** Runs [pass] for each account within one shared budget; true only when every pass settled in time. */
internal suspend fun runDueHistoryBackupPasses(
    accounts: List<String>,
    budgetMillis: Long,
    pass: suspend (String) -> Unit,
): Boolean {
    var settled = true
    val finished =
        withTimeoutOrNull(budgetMillis) {
            for (account in accounts) {
                if (runCatchingCancellable { pass(account) }.isFailure) settled = false
            }
        }
    return finished != null && settled
}
