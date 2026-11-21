package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import dev.ipf.marmotkit.BackupRestorePhaseFfi
import dev.ipf.marmotkit.BackupRestoreStatusFfi
import dev.ipf.marmotkit.BackupRestoreSubscription
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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

/** One pending-words flow per MDK account at a time: begin, confirm and forget never interleave across screens. */
private val setupMutex = Mutex()

internal data class HistoryBackupsState(
    val status: HistoryBackupStatus = HistoryBackupStatus.Loading,
    val setup: RecoveryWordsSetup? = null,
    val restore: HistoryRestoreProgress? = null,
    val running: HistoryBackupAction? = null,
    val problem: HistoryBackupProblem? = null,
)

private const val DEVICE_GROUP_REFUSED_PREFIX = "device group refused: "

/** MDK's fixed refusal code for a backup command, or null for any other failure. */
private fun backupRefusalCode(error: Throwable): String? =
    (error as? MarmotKitException.Runtime)
        ?.details
        ?.takeIf { it.startsWith(DEVICE_GROUP_REFUSED_PREFIX) }
        ?.removePrefix(DEVICE_GROUP_REFUSED_PREFIX)
        ?.trim()

internal fun historyBackupProblem(error: Throwable): HistoryBackupProblem =
    when (backupRefusalCode(error)) {
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
 * Drives one account's history backups through MDK. MDK owns eligibility, the words, publication and restore;
 * this controller sends what the user chose and shows what MDK answered. One action runs at a time; a second
 * tap while one runs is dropped.
 *
 * Recovery words live only in [state] while their setup flow is open. [close] forgets them here and in MDK, so the
 * owner calls it when the screen leaves composition.
 */
@Suppress("TooManyFunctions")
@Stable
internal class HistoryBackupsController(
    private val marmot: MarmotInterface,
    val accountRef: String,
    private val scope: CoroutineScope,
    /** Whether the captured account and runtime still own this screen; late results are dropped once false. */
    private val isCurrent: () -> Boolean = { true },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Called when MDK reports this device writes the account's backups, so the background schedule exists. */
    private val onWriting: () -> Unit = {},
    /** Outlives the screen so MDK still forgets unconfirmed words after the screen's own scope is cancelled. */
    private val cleanupScope: CoroutineScope = scope,
) {
    private val mutableState = MutableStateFlow(HistoryBackupsState())
    val state: StateFlow<HistoryBackupsState> = mutableState.asStateFlow()

    private var actionJob: Job? = null
    private var watchJob: Job? = null
    private var closed = false

    fun refresh() = run(HistoryBackupAction.Refresh) { reloadSettings() }

    /** Asks MDK for new words. Words already on screen are replaced; MDK forgets the old ones too. */
    fun beginSetup() =
        run(HistoryBackupAction.BeginSetup) {
            val words =
                setupMutex.withLock {
                    var reachedMdk = false
                    try {
                        setupCommand {
                            reachedMdk = true
                            beginRecoveryWords(accountRef)
                        }
                    } catch (retired: CancellationException) {
                        // MDK may hold words this screen will never show; forget them before a replacement can begin.
                        if (reachedMdk) {
                            withContext(dispatcher + NonCancellable) {
                                runCatching { marmot.cancelRecoveryWords(accountRef) }
                            }
                        }
                        throw retired
                    }
                }
            // A closed screen never shows them; close() already asked MDK to forget them.
            if (closed) return@run
            mutableState.update {
                it.copy(setup = RecoveryWordsSetup(words.words, words.confirmPositions.map(UInt::toInt)))
            }
        }

    fun continueToConfirm() = mutableState.update { it.copy(setup = it.setup?.confirming(), problem = null) }

    /** Sends the typed words in position order. A refusal keeps the setup so the user can try again. */
    fun confirm(answers: List<String>) =
        run(HistoryBackupAction.Confirm) {
            setupMutex.withLock { setupCommand { confirmRecoveryWords(accountRef, answers.map(String::trim)) } }
            mutableState.update { it.copy(setup = null) }
            // Confirming makes this device the writer; schedule even if the follow-up read fails.
            withContext(dispatcher) { runCatching(onWriting) }
            reloadSettings()
        }

    /** Leaves setup without changing current backups. Ignored while a confirmation is being sent. */
    fun cancelSetup() {
        if (mutableState.value.setup == null) return
        run(HistoryBackupAction.CancelSetup) {
            setupMutex.withLock { withContext(dispatcher) { marmot.cancelRecoveryWords(accountRef) } }
            mutableState.update { it.copy(setup = null) }
        }
    }

    fun turnOff() =
        run(HistoryBackupAction.TurnOff) {
            native { turnOffBackups(accountRef) }
            reloadSettings()
        }

    /** Starts a history-only restore. The words go straight to MDK; nothing here keeps a copy. */
    fun beginRestore(words: String) =
        run(HistoryBackupAction.BeginRestore) {
            val status = native { beginBackupRestore(accountRef, words) }
            applyRestore(status)
        }

    fun cancelRestore() {
        val operation = mutableState.value.restore?.operation ?: return
        run(HistoryBackupAction.CancelRestore) {
            native { cancelBackupRestore(accountRef, operation) }
            applyRestore(native { backupRestoreStatus(accountRef) })
        }
    }

    fun dismissProblem() = mutableState.update { it.copy(problem = null) }

    /** Follows restore progress until [close]. Closing the watch never cancels a restore. */
    fun watchRestore() {
        if (watchJob?.isActive == true || closed) return
        watchJob =
            // Acquisition, reads and close all enter the binding off the main thread.
            scope.launch(dispatcher) {
                if (!isCurrent()) return@launch
                var subscription: BackupRestoreSubscription? = null
                try {
                    withContext(NonCancellable) { subscription = marmot.watchBackupRestore(accountRef) }
                    coroutineContext.ensureActive()
                    val watch = checkNotNull(subscription)
                    watch.snapshot()?.takeIf { isCurrent() }?.let(::applyRestore)
                    while (isCurrent()) {
                        val next = watch.next() ?: break
                        if (isCurrent()) applyRestore(next)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A closed worker ends observation; the status row keeps the last report.
                } finally {
                    subscription?.close()
                }
            }
    }

    /** Forgets unconfirmed words here and in MDK and stops observing. Restores keep running in MDK. */
    fun close() {
        if (closed) return
        closed = true
        watchJob?.cancel()
        val current = mutableState.value
        val pending =
            current.setup != null ||
                current.running == HistoryBackupAction.BeginSetup ||
                current.running == HistoryBackupAction.Confirm
        mutableState.update { it.copy(setup = null) }
        if (pending) forgetPendingWords()
        // An admitted setup command still settles under NonCancellable and holds the lock the cleanup waits for.
        actionJob?.cancel()
    }

    private fun forgetPendingWords() {
        // Enter the mutex queue before a replacement screen can begin setup.
        cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                setupMutex.withLock { withContext(dispatcher) { marmot.cancelRecoveryWords(accountRef) } }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // MDK keeps words only in memory and forgets them when the runtime closes.
            }
        }
    }

    /** Keep the setup lock until an admitted native command settles, even if the screen leaves. */
    private suspend fun <T> setupCommand(call: suspend MarmotInterface.() -> T): T {
        coroutineContext.ensureActive()
        if (closed || !isCurrent()) throw CancellationException("History backups owner retired")
        val result = withContext(dispatcher + NonCancellable) { marmot.call() }
        if (closed || !isCurrent()) throw CancellationException("History backups owner retired")
        return result
    }

    /** Runs one native call off the main thread; a retired owner never reaches MDK or sees its answer. */
    private suspend fun <T> native(call: suspend MarmotInterface.() -> T): T {
        if (closed || !isCurrent()) throw CancellationException("History backups owner retired")
        val result = withContext(dispatcher) { marmot.call() }
        if (closed || !isCurrent()) throw CancellationException("History backups owner retired")
        return result
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun reloadSettings() {
        val status =
            try {
                val settings = native { backupSettings(accountRef) }
                if (settings.enabled && settings.thisDeviceWrites) withContext(dispatcher) { runCatching(onWriting) }
                if (settings.enabled) HistoryBackupStatus.On(settings.thisDeviceWrites) else HistoryBackupStatus.Off
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (historyBackupProblem(error) == HistoryBackupProblem.Unavailable) {
                    HistoryBackupStatus.Unavailable
                } else {
                    HistoryBackupStatus.LoadFailed
                }
            }
        if (!closed && isCurrent()) mutableState.update { it.copy(status = status) }
    }

    private fun applyRestore(status: BackupRestoreStatusFfi) {
        val progress = HistoryRestoreProgress.from(status)
        mutableState.update { current ->
            if (current.restore != null && current.restore.revision > progress.revision) {
                current
            } else {
                current.copy(restore = progress.takeUnless { it.phase == BackupRestorePhaseFfi.IDLE })
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run(
        action: HistoryBackupAction,
        block: suspend () -> Unit,
    ) {
        if (closed || actionJob?.isActive == true) return
        mutableState.update { it.copy(running = action, problem = null) }
        actionJob =
            scope.launch {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val problem = historyBackupProblem(error)
                    mutableState.update {
                        it.copy(
                            problem = problem,
                            status =
                                if (problem == HistoryBackupProblem.Unavailable) {
                                    HistoryBackupStatus.Unavailable
                                } else {
                                    it.status
                                },
                            setup = if (problem == HistoryBackupProblem.SetupExpired) null else it.setup,
                        )
                    }
                } finally {
                    mutableState.update { if (it.running == action) it.copy(running = null) else it }
                }
            }
    }
}
