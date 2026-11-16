package dev.ipf.whitenoise.android.audio

internal fun ConversationDictationController.cancelSession() {
    clearMicrophoneRecovery()
    recoveryHandedToComposer = false
    retainedAudioFailure = null
    completion.draftRecovery.reset()
    if (state.sessionId != null) conversationDictationDiagnostic("event=session_finished outcome=cancelled")
    if (state is ConversationDictationState.CheckingProvider) {
        emitReadiness(ConversationDictationReadinessPhase.Cancelled)
    }
    clearRecognitionSession(cancel = true)
    resetTranscriptSession()
    state = ConversationDictationState.Idle
}

/** Handles involuntary teardown without routing already recognized text through cancellation. */
internal fun ConversationDictationController.abortSessionPreservingTranscript(reason: String) {
    conversationDictationDiagnostic(
        "event=session_abort reason=$reason accepted=${!deliveryInProgress} " +
            "has_text=${accumulatedTranscript.isNotBlank() || completion.transcript.isNotBlank()}",
    )
    if (deliveryInProgress) {
        conversationDictationDiagnostic("event=session_abort accepted=false reason=delivery_in_progress")
        return
    }
    val current = state
    val sessionId = current.sessionId
    val target = current.target
    val transcript = accumulatedTranscript.trim().ifBlank { completion.transcript.trim() }
    if (sessionId != null &&
        target != null &&
        runCatching(platform::callerAudioHasPending).getOrDefault(true)
    ) {
        failWithRetainedCallerAudio(
            sessionId,
            target,
            unresolvedRecognitionFailure ?: ConversationDictationFailure.Unknown,
            recognizedTranscript = transcript.ifBlank { null },
        )
    } else if (transcript.isNotBlank() && sessionId != null && target != null) {
        if (completion.dispatch.dispatchedSessionId == sessionId) {
            retainUndeliveredTranscript(sessionId, target, transcript)
        } else {
            preserveTranscriptInLatestDraft(
                sessionId,
                target,
                transcript,
                failureCause = interruptedTranscriptFailure(),
            )
        }
    } else if (sessionId != null &&
        target != null &&
        unresolvedRecognitionFailure == ConversationDictationFailure.Interrupted
    ) {
        fail(
            sessionId,
            target,
            ConversationDictationFailure.Interrupted,
            captureEnd = DictationFailureCapture.CancelIncomplete,
        )
    } else {
        cancelSession()
    }
}

internal fun ConversationDictationController.interruptedTranscriptFailure(): ConversationDictationFailure? {
    val incomplete =
        generation.session != null ||
            unresolvedRecognitionFailure != null ||
            runCatching(platform::callerAudioHasPending).getOrDefault(true)
    return if (incomplete) unresolvedRecognitionFailure ?: ConversationDictationFailure.Unknown else null
}
