package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import dev.ipf.marmotkit.BackupRestorePhaseFfi
import dev.ipf.marmotkit.BackupRestoreStatusFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/** One pending-words flow per MDK account at a time: begin, confirm and forget never interleave across screens. */
private val setupMutex = Mutex()

/**
 * Drives one account's history backups through MDK. MDK owns eligibility, the words, publication and restore;
 * this controller sends what the user chose and shows what MDK answered. One action runs at a time; a second
 * tap while one runs is dropped. Without [access] (no eligible account or runtime) the status stays Loading.
 *
 * Recovery words live only in [state] while their setup flow is open. [close] forgets them here and in MDK, so the
 * owner calls it when the screen leaves composition.
 */
@Suppress("TooManyFunctions")
@Stable
internal class HistoryBackupsController(
    private val access: HistoryBackupsAccess?,
    private val scope: CoroutineScope,
    /** Outlives the screen so MDK still forgets unconfirmed words after the screen's own scope is cancelled. */
    private val cleanupScope: CoroutineScope = scope,
) : Closeable {
    private val mutableState = MutableStateFlow(HistoryBackupsState())
    val state: StateFlow<HistoryBackupsState> = mutableState.asStateFlow()

    private var actionJob: Job? = null
    private var watchJob: Job? = null
    private var closed = false

    private fun current(): Boolean = !closed && access?.isCurrent() == true

    private fun requireCurrent() {
        if (!current()) throw CancellationException("History backups owner retired")
    }

    /**
     * Calls MDK for a current owner and drops a late answer. An [uninterruptible] call settles once admitted, even if
     * the screen leaves, so a setup command never strands MDK halfway.
     */
    private suspend fun <T> owned(
        uninterruptible: Boolean = false,
        call: suspend HistoryBackupsAccess.() -> T,
    ): T {
        requireCurrent()
        val access = checkNotNull(access)
        val result = if (uninterruptible) withContext(NonCancellable) { access.call() } else access.call()
        requireCurrent()
        return result
    }

    fun refresh() = run(HistoryBackupAction.Refresh) { reloadSettings() }

    /** Asks MDK for new words. Words already on screen are replaced; MDK forgets the old ones too. */
    fun beginSetup() =
        run(HistoryBackupAction.BeginSetup) { access ->
            val words =
                setupMutex.withLock {
                    requireCurrent()
                    try {
                        val made = withContext(NonCancellable) { access.beginWords() }
                        requireCurrent()
                        made
                    } finally {
                        // A screen that left never shows them; forget them before a replacement can begin.
                        if (!current() || !currentCoroutineContext().isActive) {
                            withContext(NonCancellable) { runCatchingCancellable { access.forgetWords() } }
                        }
                    }
                }
            mutableState.update {
                it.copy(setup = RecoveryWordsSetup(words.words, words.confirmPositions.map(UInt::toInt)))
            }
        }

    fun continueToConfirm() = mutableState.update { it.copy(setup = it.setup?.confirming(), problem = null) }

    /** Sends the typed words in position order. A refusal keeps the setup so the user can try again. */
    fun confirm(answers: List<String>) =
        run(HistoryBackupAction.Confirm) {
            setupMutex.withLock { owned(uninterruptible = true) { confirmWords(answers.map(String::trim)) } }
            mutableState.update { it.copy(setup = null) }
            reloadSettings()
        }

    /** Leaves setup without changing current backups. Ignored while a confirmation is being sent. */
    fun cancelSetup() {
        if (mutableState.value.setup == null) return
        run(HistoryBackupAction.CancelSetup) {
            setupMutex.withLock { owned { forgetWords() } }
            mutableState.update { it.copy(setup = null) }
        }
    }

    fun turnOff() =
        run(HistoryBackupAction.TurnOff) {
            owned { turnOff() }
            reloadSettings()
        }

    /** Starts a history-only restore. The words go straight to MDK; nothing here keeps a copy. */
    fun beginRestore(words: String) {
        run(HistoryBackupAction.BeginRestore) {
            applyRestore(owned { beginRestore(words) })
        }
    }

    fun cancelRestore() {
        val operation = mutableState.value.restore?.operation ?: return
        run(HistoryBackupAction.CancelRestore) {
            owned { cancelRestore(operation) }
            applyRestore(owned { restoreStatus() })
        }
    }

    /** Follows restore progress until [close]. Closing the watch never cancels a restore. */
    fun watchRestore() {
        val access = access ?: return
        if (watchJob?.isActive == true || !current()) return
        watchJob =
            scope.launch {
                // A closed worker ends observation; the status row keeps the last report.
                runCatchingCancellable { access.followRestore { if (current()) applyRestore(it) } }
            }
    }

    /** Forgets unconfirmed words here and in MDK and stops observing. Restores keep running in MDK. */
    override fun close() {
        if (closed) return
        closed = true
        watchJob?.cancel()
        val current = mutableState.value
        // beginSetup forgets words made for a retired owner itself; forgetting here could hit a newer setup.
        val pending = current.setup != null || current.running == HistoryBackupAction.Confirm
        mutableState.update { it.copy(setup = null) }
        val access = access
        if (pending && access != null) {
            // Enter the mutex queue before a replacement screen can begin setup. MDK keeps words only in memory and
            // forgets them when the runtime closes, so a failure here leaves nothing behind.
            cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatchingCancellable { setupMutex.withLock { access.forgetWords() } }
            }
        }
        // An admitted setup command still settles under NonCancellable and holds the lock the cleanup waits for.
        actionJob?.cancel()
    }

    private suspend fun reloadSettings() {
        val status =
            runCatchingCancellable { owned { settings() } }.fold(
                onSuccess = {
                    if (it.enabled) HistoryBackupStatus.On(it.thisDeviceWrites) else HistoryBackupStatus.Off
                },
                onFailure = {
                    if (historyBackupProblem(it) == HistoryBackupProblem.Unavailable) {
                        HistoryBackupStatus.Unavailable
                    } else {
                        HistoryBackupStatus.LoadFailed
                    }
                },
            )
        mutableState.update { it.copy(status = status) }
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

    private fun run(
        action: HistoryBackupAction,
        block: suspend (HistoryBackupsAccess) -> Unit,
    ) {
        val access = access
        if (access == null || !current() || actionJob?.isActive == true) return
        mutableState.update { it.copy(running = action, problem = null) }
        actionJob =
            scope.launch {
                try {
                    runCatchingCancellable { block(access) }.onFailure(::applyProblem)
                } finally {
                    mutableState.update { if (it.running == action) it.copy(running = null) else it }
                }
            }
    }

    private fun applyProblem(error: Throwable) {
        if (!current()) return
        val problem = historyBackupProblem(error)
        val unavailable = problem == HistoryBackupProblem.Unavailable
        mutableState.update {
            it.copy(
                problem = problem,
                status = if (unavailable) HistoryBackupStatus.Unavailable else it.status,
                setup = if (problem == HistoryBackupProblem.SetupExpired) null else it.setup,
            )
        }
    }
}
