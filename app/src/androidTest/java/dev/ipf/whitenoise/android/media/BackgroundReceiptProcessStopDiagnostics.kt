package dev.ipf.whitenoise.android.media

import android.os.SystemClock
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.permissions
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val PROCESS_STOP_READY_TIMEOUT_MILLIS = 90_000L

/**
 * Traces the process-stop fixture's existing waits without adding native or network reads. Observations come from
 * required WorkManager and ledger reads; absent observations remain distinguishable from an observed empty set.
 * The supplied snapshot reads in-memory host policy and native-approval bookkeeping, not native transfer state.
 */
internal class BackgroundReceiptProcessStopDiagnostics(
    private val snapshot: () -> JSONObject,
    private val report: (JSONObject) -> Unit = ControlledAttachmentProbe::report,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var workObservations = 0
    private var workObservedAt: Long? = null
    private var workStates: Map<WorkInfo.State, Int> = emptyMap()
    private var ledgerObservations = 0
    private var ledgerObservedAt: Long? = null
    private var ledgerCounts: Map<String, Int> = emptyMap()

    /** Keeps the fixture's original ninety-second RUNNING wait and records only its already-read work states. */
    suspend fun awaitRunningWork(
        manager: WorkManager,
        name: String,
    ): WorkInfo =
        trace(BackgroundReceiptProcessStopStage.RunningWork) {
            withTimeout(PROCESS_STOP_READY_TIMEOUT_MILLIS) {
                var info: WorkInfo? = null
                while (info == null) {
                    val infos = manager.getWorkInfosForUniqueWork(name).get()
                    observeWork(infos.map { it.state })
                    info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    if (info == null) delay(50L)
                }
                info
            }
        }

    /** Retains only closed states from an existing WorkManager read, never work IDs, tags or input data. */
    fun observeWork(states: List<WorkInfo.State>) {
        workObservations++
        workObservedAt = monotonicMillis()
        workStates = states.groupingBy { it }.eachCount()
    }

    /** Summarizes existing ledger reads using fixed event kinds; identities and response details are discarded. */
    fun observeLedger(events: List<JSONObject>) {
        ledgerObservations++
        ledgerObservedAt = monotonicMillis()
        ledgerCounts =
            listOf("get", "held", "complete", "disconnect", "hold_timeout").associateWith { kind ->
                events.count { it.optString("kind") == kind }
            }
    }

    /** Reports entry and completion or timeout without adding a deadline or replacing any original exception. */
    suspend fun <T> trace(
        stage: BackgroundReceiptProcessStopStage,
        block: suspend () -> T,
    ): T {
        val startedAt = monotonicMillis()
        emit(stage, "entry", startedAt)
        return try {
            block().also { emit(stage, "complete", startedAt) }
        } catch (timeout: TimeoutCancellationException) {
            emit(stage, "timeout", startedAt)
            throw timeout
        }
    }

    /** Snapshot failure still permits boundary reporting; diagnostic failures cannot change the fixture outcome. */
    private fun emit(
        stage: BackgroundReceiptProcessStopStage,
        event: String,
        startedAt: Long,
    ) {
        runCatching {
            val now = monotonicMillis()
            val policy = runCatching(snapshot).getOrNull()
            report(
                (policy ?: JSONObject())
                    .put("phase", "background-receipt-process-stop")
                    .put("stage", stage.name)
                    .put("event", event)
                    .put("monotonic_ms", now)
                    .put("elapsed_ms", now - startedAt)
                    .put("policy_snapshot_available", policy != null)
                    .put("work_observations", workObservations)
                    .put("work_observed_at_ms", workObservedAt ?: JSONObject.NULL)
                    .put("work_states", JSONObject(workStates.mapKeys { it.key.name }))
                    .put("ledger_observations", ledgerObservations)
                    .put("ledger_observed_at_ms", ledgerObservedAt ?: JSONObject.NULL)
                    .put("ledger_counts", JSONObject(ledgerCounts)),
            )
        }
    }
}

/** The three existing operations between policy application and the platform stop marker. */
internal enum class BackgroundReceiptProcessStopStage {
    ReceiptProcessing,
    RunningWork,
    HeldLedger,
}

/** Reads only the current in-memory policy and native-approval bookkeeping for the generated receiver. */
internal fun backgroundReceiptProcessStopDiagnostics(
    state: WhiteNoiseAppState,
    account: String,
): BackgroundReceiptProcessStopDiagnostics =
    BackgroundReceiptProcessStopDiagnostics(
        snapshot = {
            backgroundReceiptPolicySnapshot(
                state.automaticAttachmentPolicy(account),
                MediaAutoDownloadType.entries.toSet(),
                emptySet(),
                permissions(state).appliedRevision(account) != null,
            )
        },
    )
