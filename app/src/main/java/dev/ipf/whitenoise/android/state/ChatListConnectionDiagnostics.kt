package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.whitenoise.android.diagnostics.PerformanceConnectivity
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrigger

/** Captures existing delivery settings without account identities or a second preference owner. */
internal data class ChatListConnectionDiagnosticConfiguration(
    val fcm: Boolean,
    val keepConnected: Boolean,
    val connectivity: PerformanceConnectivity,
)

/**
 * One anonymous readiness episode in the existing opt-in, bounded WNPerf session.
 * Only closed phases, delivery flags and bounded runtime/attempt counters reach the sink.
 * The collector adds no poll, wake lock, network call or retained connection history.
 */
internal class ChatListConnectionDiagnostics(
    private val configuration: () -> ChatListConnectionDiagnosticConfiguration,
    private val beginTrace: () -> PerformanceTrace? = {
        PerformanceDiagnostics.begin(PerformanceOperation.CHAT_LIST_REFRESH, PerformanceTrigger.CHAT_LIST_READINESS)
    },
    private val record: (PerformanceTrace, PerformancePhase, Long, Long, Int, Int, PerformanceConnectivity) -> Unit =
        { trace, phase, elapsed, duration, generation, attempt, connectivity ->
            PerformanceDiagnostics.record(
                trace = trace,
                phase = phase,
                elapsedMs = elapsed,
                durationMs = duration,
                count = generation,
                attempt = attempt,
                connectivity = connectivity,
            )
        },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var trace: PerformanceTrace? = null
    private var startedAtMs = 0L

    /** Begins an observed episode only after a local diagnostic session was explicitly enabled. */
    fun begin(
        phase: PerformancePhase,
        state: ChatListConnectionState,
    ) {
        trace = beginTrace()
        if (trace == null) return
        startedAtMs = nowMs()
        val settings = configuration()
        event(
            if (settings.fcm) PerformancePhase.CONNECTION_MODE_FCM else PerformancePhase.CONNECTION_MODE_LOCAL,
            state,
        )
        event(
            if (settings.keepConnected) {
                PerformancePhase.CONNECTION_KEEP_CONNECTED_ON
            } else {
                PerformancePhase.CONNECTION_KEEP_CONNECTED_OFF
            },
            state,
        )
        event(phase, state)
    }

    /** Records a source-confirmed boundary against this episode, without serializing owner keys. */
    fun event(
        phase: PerformancePhase,
        state: ChatListConnectionState,
        durationMs: Long = 0L,
    ) {
        val current = trace ?: return
        val connectivity = configuration().connectivity
        emit(current, phase, state, connectivity, durationMs)
        emit(
            current,
            when (state.phase) {
                ChatListConnectionPhase.Idle -> PerformancePhase.CONNECTION_PHASE_IDLE
                ChatListConnectionPhase.Validating -> PerformancePhase.CONNECTION_PHASE_VALIDATING
                ChatListConnectionPhase.Attempting -> PerformancePhase.CONNECTION_PHASE_ATTEMPTING
                ChatListConnectionPhase.Ready -> PerformancePhase.CONNECTION_PHASE_READY
            },
            state,
            connectivity,
            0L,
        )
    }

    /** Emits a closed boundary with bounded counters using the production diagnostic serializer. */
    private fun emit(
        current: PerformanceTrace,
        phase: PerformancePhase,
        state: ChatListConnectionState,
        connectivity: PerformanceConnectivity,
        durationMs: Long,
    ) {
        record(
            current,
            phase,
            (nowMs() - startedAtMs).coerceAtLeast(0L),
            durationMs,
            state.runtimeGeneration,
            state.sessionAttemptId.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            connectivity,
        )
    }

    /** Releases the anonymous trace when its controller/runtime owner is replaced or disposed. */
    fun clear() {
        trace = null
    }
}

/** Projects coarse cached settings for diagnostic attribution without reading native state. */
internal fun WhiteNoiseAppState.chatListConnectionDiagnosticConfiguration(): ChatListConnectionDiagnosticConfiguration {
    val signals = connectivitySignals.value
    return ChatListConnectionDiagnosticConfiguration(
        fcm = notificationDeliveryMode() == NotificationDeliveryMode.Fcm,
        keepConnected = backgroundConnectionEnabled,
        connectivity =
            when {
                !signals.hasValidatedInternet -> PerformanceConnectivity.OFFLINE
                signals.relaysConnected -> PerformanceConnectivity.ONLINE_WITH_RELAY
                else -> PerformanceConnectivity.ONLINE_NO_RELAY
            },
    )
}
