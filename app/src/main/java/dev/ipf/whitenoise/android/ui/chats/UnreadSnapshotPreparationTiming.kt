package dev.ipf.whitenoise.android.ui.chats

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.state.ChatFolderAccountState
import kotlinx.coroutines.flow.first

/**
 * Times one unread-snapshot read (#3586): the account-wide native read, then the participant-folder roster
 * resolution, each as its own WNPerf phase. A read started while [filterOpen] belongs to the open filter's
 * `unread_filter` trace, and one started while it is closed to background `unread_prepare`, so preparation for
 * a filter that never opens is not counted as filter work. Nothing is emitted, and no trace is held, unless a
 * local diagnostic session is active; both phases drop spans below the routine threshold.
 */
internal class UnreadSnapshotPreparationTiming(
    filterOpen: Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val trace =
        PerformanceDiagnostics.begin(
            if (filterOpen) PerformanceOperation.UNREAD_FILTER else PerformanceOperation.UNREAD_PREPARE,
        )
    private val startedAt = clock()
    private var phaseStartedAt = startedAt

    /** Marks the end of the account-wide native read. */
    fun readFinished() = record(PerformancePhase.UNREAD_SNAPSHOT_READ, count = null)

    /** Marks the end of roster resolution for the [groups] participant-folder candidates. */
    fun rostersFinished(groups: Int) = record(PerformancePhase.UNREAD_ROSTER_READY, groups)

    /** Records [phase] with its own duration and the elapsed time since the preparation began. */
    private fun record(
        phase: PerformancePhase,
        count: Int?,
    ) {
        if (trace == null) return
        val now = clock()
        PerformanceDiagnostics.record(
            trace,
            phase,
            elapsedMs = now - startedAt,
            durationMs = now - phaseStartedAt,
            count = count,
        )
        phaseStartedAt = now
    }
}

/**
 * Records, once per activation, the time from opening the unread filter to the frame after its complete
 * result was composed (#3586). A result prepared before activation reports `success`. One that the filter
 * had to wait for reports `pending`, so a slow first frame is attributable to a missing snapshot.
 */
@Suppress("FunctionNaming")
@Composable
internal fun RecordUnreadFilterFirstResultFrame(
    active: Boolean,
    complete: Boolean,
    rowCount: Int,
) {
    val currentComplete by rememberUpdatedState(complete)
    val currentRowCount by rememberUpdatedState(rowCount)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.UNREAD_FILTER) ?: return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        val preparedAtActivation = currentComplete
        snapshotFlow { currentComplete }.first { it }
        withFrameMillis { }
        PerformanceDiagnostics.record(
            trace,
            PerformancePhase.UNREAD_FILTER_FIRST_RESULT_FRAME,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            result = if (preparedAtActivation) PerformanceResult.SUCCESS else PerformanceResult.PENDING,
            count = currentRowCount,
        )
    }
}

/**
 * The changes that may request a fresh unread snapshot. [openRowRevision] is the chat list's row revision
 * while the unread filter is open and null while it is closed, so opening the filter and row changes under it
 * each start one conflated read.
 */
internal data class UnreadSnapshotTrigger(
    val membershipRevision: Long,
    val folders: Map<String, ChatFolderAccountState>,
    val openRowRevision: Long?,
) {
    /** Whether the only change since [previous] is the filter closing, which needs no fresh read. */
    fun closesFilterAfter(previous: UnreadSnapshotTrigger?): Boolean =
        previous != null && openRowRevision == null && this == previous.copy(openRowRevision = null)

    /**
     * Whether only window membership changed since [previous] while the filter stayed closed over the same
     * folders, which needs a fresh read only once the prepared result stops serving the window.
     */
    fun changesClosedMembershipAfter(previous: UnreadSnapshotTrigger?): Boolean =
        previous != null && previous.openRowRevision == null && openRowRevision == null && folders == previous.folders
}
