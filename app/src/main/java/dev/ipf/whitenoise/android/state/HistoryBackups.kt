package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import dev.ipf.marmotkit.BackupRestorePhaseFfi
import dev.ipf.marmotkit.BackupRestoreStatusFfi
import dev.ipf.marmotkit.BackupSettingsFfi
import dev.ipf.marmotkit.RecoveryWordsFfi

/** Where the account's history backups stand, as MDK last reported them. */
internal sealed interface HistoryBackupStatus {
    data object Loading : HistoryBackupStatus

    /** This build's MarmotKit refuses backups (`roster_unsupported`): device groups are off. */
    data object Unavailable : HistoryBackupStatus

    data object Off : HistoryBackupStatus

    /** On; [thisDeviceWrites] says whether this device writes the account's backups. */
    data class On(
        val thisDeviceWrites: Boolean,
    ) : HistoryBackupStatus

    data object LoadFailed : HistoryBackupStatus
}

/** Why an action did not go through, sorted into the advice the screen gives. */
internal enum class HistoryBackupProblem {
    /** Backups are not available in this build. */
    Unavailable,

    /** The typed confirmation words do not match. The words stay valid for another try. */
    WrongWords,

    /** The account's devices are settling another change; try again shortly. */
    Busy,

    /** MDK could not read the account's devices. */
    RosterUnavailable,

    /** The restore words are not 12 valid recovery words. */
    InvalidRestoreWords,

    /** A restore is already running. */
    RestoreBusy,

    /** The new words timed out or were forgotten; start over. */
    SetupExpired,

    Failed,
}

/**
 * New recovery words for one setup flow. Held only in memory, never in saved state or logs: [toString] is
 * redacted so the words cannot leak through string templates, crash reports or state dumps.
 */
@Stable
internal class RecoveryWordsSetup(
    val words: List<String>,
    /** The two 1-based positions to type back, ascending. */
    val confirmPositions: List<Int>,
    val confirming: Boolean = false,
) {
    /** The words are shown once: confirmation keeps only the positions, so they cannot be displayed again. */
    fun confirming(): RecoveryWordsSetup = RecoveryWordsSetup(emptyList(), confirmPositions, confirming = true)

    /** Formats setup state without exposing the words. */
    override fun toString(): String = "RecoveryWordsSetup(words=[${words.size} redacted], confirming=$confirming)"
}

/** One restore's progress, reduced from MDK's aggregate status. */
internal data class HistoryRestoreProgress(
    val phase: BackupRestorePhaseFfi,
    /** Opaque token MDK uses to cancel this restore; null once no operation is named. */
    val operation: String?,
    val importedMessages: Long,
    val failedParts: Long,
    val revision: Long,
) {
    val running: Boolean
        get() = phase == BackupRestorePhaseFfi.DISCOVERING || phase == BackupRestorePhaseFfi.RESTORING

    companion object {
        /** Projects the native restore status into screen state. */
        fun from(status: BackupRestoreStatusFfi): HistoryRestoreProgress =
            HistoryRestoreProgress(
                phase = status.phase,
                operation = status.operation,
                importedMessages = status.importedEvents.toLong(),
                failedParts = (status.failedArchives + status.failedBundles).toLong(),
                revision = status.revision.toLong(),
            )
    }
}

internal enum class HistoryBackupAction {
    Refresh,
    BeginSetup,
    Confirm,
    CancelSetup,
    TurnOff,
    BeginRestore,
    CancelRestore,
}

internal data class HistoryBackupsState(
    val status: HistoryBackupStatus = HistoryBackupStatus.Loading,
    val setup: RecoveryWordsSetup? = null,
    val restore: HistoryRestoreProgress? = null,
    val running: HistoryBackupAction? = null,
    val problem: HistoryBackupProblem? = null,
)

/** Maps known MarmotKit refusal codes to recovery advice. */
internal fun historyBackupProblem(error: Throwable): HistoryBackupProblem =
    when (deviceGroupRefusal(error)?.trim()) {
        "roster_unsupported" -> HistoryBackupProblem.Unavailable
        "recovery_words_mismatch" -> HistoryBackupProblem.WrongWords
        "recovery_words_not_started" -> HistoryBackupProblem.SetupExpired
        "device_group_busy" -> HistoryBackupProblem.Busy
        "roster_unavailable" -> HistoryBackupProblem.RosterUnavailable
        "backup_recovery_words", "backup_recovery_key" -> HistoryBackupProblem.InvalidRestoreWords
        "backup_restore_busy" -> HistoryBackupProblem.RestoreBusy
        else -> HistoryBackupProblem.Failed
    }

/**
 * MDK's backup commands for one captured account and runtime, off the main thread. Every call except [forgetWords]
 * refuses once [isCurrent] turns false; forgetting unconfirmed words is always safe, so cleanup still reaches MDK.
 */
internal interface HistoryBackupsAccess {
    /** Checks whether the captured account and runtime still own the screen. */
    fun isCurrent(): Boolean

    /** Reads the account’s enablement and writer role. */
    suspend fun settings(): BackupSettingsFfi

    /** Starts setup and returns the words and confirmation positions. */
    suspend fun beginWords(): RecoveryWordsFfi

    /** Submits the answers in the order requested by MarmotKit. */
    suspend fun confirmWords(answers: List<String>)

    /** Clears any unfinished setup for the captured account. */
    suspend fun forgetWords()

    /** Stops future backups while retaining existing archives. */
    suspend fun turnOff()

    /** Starts a history-only restore and returns its initial status. */
    suspend fun beginRestore(words: String): BackupRestoreStatusFfi

    /** Requests cancellation of the specified restore operation. */
    suspend fun cancelRestore(operation: String)

    /** Reads the latest restore state for the captured account. */
    suspend fun restoreStatus(): BackupRestoreStatusFfi

    /** Reports restore progress until the owner retires or MDK ends the watch. Never cancels a restore. */
    suspend fun followRestore(onStatus: (BackupRestoreStatusFfi) -> Unit)
}
