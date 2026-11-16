package dev.ipf.whitenoise.android.audio

/** Preserves an already armed silence deadline across delayed provider recovery. */
@Suppress("ReturnCount")
internal fun ConversationDictationController.armSilenceDeadline(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    if (state.sessionId != sessionId || finishRequested) return
    val silenceMillis = target.finishAfterSilenceMillis ?: return
    val now = elapsedRealtime()
    val capturedSilence =
        if (generation.callerAudioEndpointingActive) {
            platform.callerAudioSilenceMillis()
        } else {
            if (accumulatedTranscript.isBlank()) return
            null
        }
    val deadline =
        if (generation.callerAudioEndpointingActive) {
            now + (silenceMillis - (capturedSilence ?: 0L)).coerceAtLeast(0L)
        } else {
            silenceDeadlineElapsedMillis ?: (now + silenceMillis)
        }
    silenceDeadlineElapsedMillis = deadline
    val remainingMillis = (deadline - now).coerceAtLeast(0L)
    silenceTimeoutHandle?.cancel()
    conversationDictationDiagnostic(
        "event=silence_check_armed delay_ms=$remainingMillis " +
            "caller_audio=${generation.callerAudioEndpointingActive} speech_seen=${capturedSilence != null}",
    )
    silenceTimeoutHandle =
        scheduleTimeout(remainingMillis) {
            checkSilenceDeadline(sessionId, target, silenceMillis)
        }
}

/** Evaluates one deadline against the current capture rather than its originally scheduled state. */
internal fun ConversationDictationController.checkSilenceDeadline(
    sessionId: Long,
    target: ConversationDictationTarget,
    silenceMillis: Long,
) {
    if (state.sessionId != sessionId || finishRequested) return
    val currentCapturedSilence =
        if (generation.callerAudioEndpointingActive) {
            platform.callerAudioSilenceMillis()
        } else {
            null
        }
    when {
        generation.callerAudioEndpointingActive && currentCapturedSilence == null -> {
            conversationDictationDiagnostic("event=silence_check_rearmed reason=no_speech")
            silenceDeadlineElapsedMillis = null
            armSilenceDeadline(sessionId, target)
        }
        generation.callerAudioEndpointingActive ->
            finishAfterCallerAudioSilence(
                sessionId,
                target,
                silenceMillis,
                requireNotNull(currentCapturedSilence),
            )
        !generation.hasSpeech && accumulatedTranscript.isNotBlank() -> {
            if (requestedDeliveryMode == null) {
                completion.intent.choose(target.silenceDeliveryMode, automatic = true)
            }
            finishRequested = true
            cancelPendingRestart()
            clearRecognitionGeneration(
                cancel = true,
                onAudioCaptureFinished = { finishPlaybackInterruption(sessionId) },
            )
            finalizeAccumulatedTranscript(sessionId, target)
        }
    }
}

/** Rechecks actual microphone activity and drains the tail before automatic completion. */
internal fun ConversationDictationController.finishAfterCallerAudioSilence(
    sessionId: Long,
    target: ConversationDictationTarget,
    thresholdMillis: Long,
    capturedSilenceMillis: Long,
) {
    if (capturedSilenceMillis < thresholdMillis) {
        conversationDictationDiagnostic(
            "event=silence_check_rearmed reason=speech silence_ms=$capturedSilenceMillis",
        )
        armSilenceDeadline(sessionId, target)
    } else {
        conversationDictationDiagnostic(
            "event=silence_check_complete silence_ms=$capturedSilenceMillis",
        )
        silenceTimeoutHandle?.cancel()
        silenceTimeoutHandle = null
        silenceDeadlineElapsedMillis = null
        completeAfterSilence(target)
    }
}

/**
 * Completes a session the silence timer ended, using the stored automatic-completion choice.
 *
 * A Paste or Send already pressed has recorded its own choice, so this returns rather than
 * replacing it: the stored setting only ever decides a completion nobody asked for.
 */
internal fun ConversationDictationController.completeAfterSilence(target: ConversationDictationTarget) {
    if (requestedDeliveryMode != null) return
    stopWithDeliveryMode(target.silenceDeliveryMode, automatic = true)
}
