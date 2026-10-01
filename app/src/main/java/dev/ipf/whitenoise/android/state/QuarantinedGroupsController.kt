package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

internal enum class QuarantineRecoveryOutcome { Recovered, StillQuarantined, Failed }

internal data class QuarantinedGroupsUiState(
    val available: Boolean = true,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val rows: List<QuarantinedGroupRow> = emptyList(),
    val recoveringGroup: String? = null,
    val outcome: QuarantineRecoveryOutcome? = null,
) {
    val busy: Boolean get() = loading || recoveringGroup != null
}

/**
 * One screen's transient presentation, called only on the Main dispatcher.
 * Native recovery may finish after disposal or owner loss; suppressing that outcome here
 * does not undo recovery. The active account's normal chat projection remains authoritative.
 */
internal class QuarantinedGroupsController(
    private val access: QuarantinedGroupsAccess?,
    private val scope: CoroutineScope,
) : Closeable {
    private val mutable = MutableStateFlow(QuarantinedGroupsUiState(available = access != null))
    val state = mutable.asStateFlow()
    private var closed = false
    private var job: Job? = null
    private var running = false
    private var refreshRequested = false

    private fun current(): Boolean = !closed && access?.isCurrent() == true

    /** Repeated refreshes coalesce into one additional read after the admitted operation. */
    fun refresh() {
        if (!current()) {
            finishPresentation()
            return
        }
        if (running) {
            refreshRequested = true
            return
        }
        start { reload() }
    }

    fun recover(groupId: String) {
        if (!current()) {
            finishPresentation()
            return
        }
        if (running || !state.value.loaded || state.value.rows.none { it.groupId == groupId }) return
        start {
            mutable.value = state.value.copy(recoveringGroup = groupId, outcome = null)
            val outcome =
                try {
                    if (access!!.retry(groupId)) {
                        QuarantineRecoveryOutcome.Recovered
                    } else {
                        QuarantineRecoveryOutcome.StillQuarantined
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    QuarantineRecoveryOutcome.Failed
                }
            if (current()) {
                val rows =
                    if (outcome == QuarantineRecoveryOutcome.Recovered) {
                        state.value.rows.filterNot { it.groupId == groupId }
                    } else {
                        state.value.rows
                    }
                mutable.value = state.value.copy(rows = rows, recoveringGroup = null, outcome = outcome)
                refreshRequested = false
                reload()
            }
        }
    }

    private fun start(work: suspend () -> Unit) {
        running = true
        job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    work()
                    while (current() && refreshRequested) {
                        refreshRequested = false
                        reload()
                    }
                } finally {
                    running = false
                    finishPresentation()
                }
            }
    }

    /** Losing an owner retires only this controller's transient presentation. */
    private fun finishPresentation() {
        if (closed) return
        mutable.value =
            if (access?.isCurrent() == true) {
                state.value.copy(loading = false, recoveringGroup = null)
            } else {
                QuarantinedGroupsUiState(available = false)
            }
    }

    private suspend fun reload() {
        if (!current()) return
        mutable.value = state.value.copy(loading = true)
        try {
            val rows = access!!.load().sortedBy { it.groupId }
            if (current()) mutable.value = state.value.copy(rows = rows, loaded = true, loadFailed = false)
        } catch (
            cancelled: CancellationException,
        ) {
            throw cancelled
        } catch (
            _: Exception,
        ) {
            if (current()) mutable.value = state.value.copy(loadFailed = true)
        } finally {
            if (current()) mutable.value = state.value.copy(loading = false)
        }
    }

    override fun close() {
        closed = true
        job?.cancel()
    }
}
