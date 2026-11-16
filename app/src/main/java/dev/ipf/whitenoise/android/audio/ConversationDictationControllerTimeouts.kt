package dev.ipf.whitenoise.android.audio

import java.util.concurrent.atomic.AtomicBoolean

/** Emits elapsed, PII-free provider-readiness diagnostics. */
internal fun ConversationDictationController.emitReadiness(phase: ConversationDictationReadinessPhase) {
    val startedAt = readinessStartedAtMillis ?: elapsedRealtime()
    onReadinessEvent(
        ConversationDictationReadinessEvent(
            phase = phase,
            elapsedMillis = (elapsedRealtime() - startedAt).coerceAtLeast(0L),
        ),
    )
}

/** Arms a watchdog that becomes inert as soon as its recognizer generation is replaced. */
internal fun ConversationDictationController.armGenerationTimeout(
    sessionId: Long,
    generationId: Long,
    delayMillis: Long,
    phase: ConversationDictationRecognitionDeadlines.GenerationPhase,
    callback: () -> Unit,
) {
    recognitionDeadlines.armGeneration(sessionId, phase) {
        diagnosticTimeout(
            sessionId,
            delayMillis,
            phase.diagnosticName,
            isCurrent = { state.sessionId == sessionId && generation.activeId == generationId },
            callback = callback,
        )
    }
}

/** Arms the logical-session safety bound independently of provider generation churn. */
internal fun ConversationDictationController.armSessionTimeout(
    sessionId: Long,
    delayMillis: Long,
    phase: String,
    callback: () -> Unit,
) {
    recognitionDeadlines.armSession {
        diagnosticTimeout(
            sessionId,
            delayMillis,
            phase,
            isCurrent = { state.sessionId == sessionId },
            callback = callback,
        )
    }
}

internal fun ConversationDictationController.diagnosticTimeout(
    sessionId: Long,
    delayMillis: Long,
    phase: String,
    isCurrent: () -> Boolean,
    callback: () -> Unit,
): ConversationDictationTimeoutHandle {
    DictationDiagnostics.record("event=watchdog_armed session=$sessionId phase=$phase timeout_ms=$delayMillis")
    val pending = AtomicBoolean(true)
    val startedAt = elapsedRealtime()
    val handle =
        scheduleTimeout(delayMillis) {
            if (pending.getAndSet(false)) {
                val accepted = isCurrent()
                DictationDiagnostics.record(
                    "event=watchdog_fired session=$sessionId phase=$phase accepted=$accepted " +
                        "elapsed_ms=${elapsedRealtime() - startedAt}",
                )
                if (accepted) callback()
            }
        }
    return ConversationDictationTimeoutHandle {
        if (pending.getAndSet(false)) {
            DictationDiagnostics.record("event=watchdog_cancelled session=$sessionId phase=$phase")
        }
        handle.cancel()
    }
}
