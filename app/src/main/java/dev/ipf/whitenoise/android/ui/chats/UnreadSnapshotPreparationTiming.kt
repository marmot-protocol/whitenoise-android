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
 * Times one background unread-snapshot preparation (#3586): the account-wide native read, then the
 * participant-folder roster resolution, each as its own WNPerf phase. Nothing is emitted, and no trace is
 * held, unless a local diagnostic session is active; both phases drop spans below the routine threshold.
 */
internal class UnreadSnapshotPreparationTiming(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val trace = PerformanceDiagnostics.begin(PerformanceOperation.UNREAD_FILTER)
    private val startedAt = clock()
    private var phaseStartedAt = startedAt

    /** Marks the end of the account-wide native read. */
    fun readFinished() = record(PerformancePhase.UNREAD_FILTER_SNAPSHOT_READ, count = null)

    /** Marks the end of roster resolution for the [groups] participant-folder candidates. */
    fun rostersFinished(groups: Int) = record(PerformancePhase.UNREAD_FILTER_ROSTER_READY, groups)

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
 * The changes that request a fresh unread snapshot. [openRowRevision] is the chat list's row revision while
 * the unread filter is open and null while it is closed, so opening the filter and row changes under it
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
}
