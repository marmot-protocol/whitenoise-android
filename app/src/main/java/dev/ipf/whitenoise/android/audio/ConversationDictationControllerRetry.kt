package dev.ipf.whitenoise.android.audio

/** Revalidates retained text without upgrading an automatic completion to an explicit Send. */
internal fun ConversationDictationController.retryRetainedTranscript(
    failed: ConversationDictationState.Failed,
    transcript: String,
) {
    if (recoveryHandedToComposer) return
    conversationDictationDiagnostic("event=retry path=retained_transcript")
    val retrySend =
        failed.reason == ConversationDictationFailure.SendBlocked ||
            requestedDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish
    val automatic = !completion.intent.explicit
    completion.intent.reset()
    val retryMode =
        if (retrySend) {
            // A saved recovery that was edited stops above in retainSupersededSendDraft; an
            // unsaved transcript is sent with the composer as it is now, like the first Send.
            ConversationDictationDeliveryMode.SendOnFinish
        } else {
            ConversationDictationDeliveryMode.PasteIntoDraft
        }
    completion.intent.choose(retryMode, automatic = automatic)
    finishRequested = true
    state = ConversationDictationState.Processing(failed.sessionId, failed.target)
    validateAndDeliverTranscript(failed.sessionId, failed.target, transcript)
}

/** Keeps an intentionally edited saved draft and its original deadline without restarting recovery. */
@Suppress("ReturnCount")
internal fun ConversationDictationController.retainSupersededSendDraft(
    failed: ConversationDictationState.Failed,
    pendingAudio: Boolean,
): Boolean {
    val hasBlockedText =
        failed.reason == ConversationDictationFailure.SendBlocked && !failed.retainedTranscript.isNullOrBlank()
    if (!hasBlockedText) return false
    val savedBefore =
        completion.draftRecovery.hasSavedTranscript(
            failed.sessionId,
            failed.target,
            failed.retainedTranscript,
            requireVerifiedRecovery = false,
        )
    if (!savedBefore || canRetryRecoveredSend) return false
    if (pendingAudio) return false
    val recovery = recoverRecognizedDraftResult(failed.sessionId, failed.target, failed.retainedTranscript)
    if (state !== failed) return true
    state =
        failed.copy(
            draftRecovered = recovery.draftRecovered,
            draftSuperseded = recovery == ConversationDictationDraftRecovery.Result.Superseded,
        )
    if (recovery == ConversationDictationDraftRecovery.Result.Unavailable) {
        pendingForegroundRecoverySessionId = failed.sessionId
        reattachForegroundRecoveryAfterClosure()
    } else if (!foregroundMicrophoneRequired) {
        releaseDurableSessionLease()
    }
    return true
}

/** Retranscribes the sealed audio while preserving its original completion intent. */
internal fun ConversationDictationController.retryRetainedCallerAudio(failed: ConversationDictationState.Failed) {
    platform.acknowledgeRetainedCallerAudioFailure()
    state = ConversationDictationState.Starting(failed.sessionId, failed.target)
    armCallerAudioDrainTimeout(failed.sessionId, failed.target)
    recognition.resetFailure()
    // Retry starts a fresh finish measurement; the failed attempt's clock was cleared when it failed.
    finishTiming.tap(elapsedRealtime())
    val failure = failed.cause ?: failed.reason
    val delayMillis =
        failure.retainedCallerAudioRetryDelayMillis(if (failure.hasProviderCapacityBackoff) 1 else 0)
    conversationDictationDiagnostic(
        "event=caller_audio_retry_requested source=failure delay_ms=$delayMillis",
    )
    if (delayMillis == 0L) {
        startRecognition(failed.sessionId, failed.target)
    } else {
        scheduleRetainedCallerAudioRetry(failed.sessionId, failed.target, delayMillis)
        // Pending foreground ownership protects a background return during provider backoff.
        // Promotion acknowledgment cannot start recognition before this timer completes.
        ensureDurableSession(failed.sessionId, failed.target)
    }
}

/** Bounds exact-chunk recovery, then completes safe accumulated text through the chosen action. */
internal fun ConversationDictationController.retryRetainedCallerAudioOrFail(
    sessionId: Long,
    target: ConversationDictationTarget,
    failure: ConversationDictationFailure,
) {
    val delayMillis = failure.retainedCallerAudioRetryDelayMillis(recognition.capacityAttempts)
    conversationDictationDiagnostic(
        "event=caller_audio_retry_scheduled failure=${failure.name} delay_ms=$delayMillis",
    )
    finishTiming.retryScheduled()
    if (delayMillis == 0L) {
        startRecognition(sessionId, target)
    } else {
        scheduleRetainedCallerAudioRetry(sessionId, target, delayMillis)
    }
}

/** Lets a disconnected or still-busy provider release capacity without dropping sealed PCM. */
internal fun ConversationDictationController.scheduleRetainedCallerAudioRetry(
    sessionId: Long,
    target: ConversationDictationTarget,
    delayMillis: Long,
) {
    if (state.sessionId != sessionId || !finishRequested) {
        conversationDictationDiagnostic("event=caller_audio_retry_skipped reason=state")
        return
    }
    cancelPendingRestart()
    state = ConversationDictationState.Starting(sessionId, target)
    val scheduledRestartId = restart.reserve()
    restart.install(
        scheduledRestartId,
        scheduleTimeout(delayMillis) {
            val ownsScheduledRestart =
                state.sessionId == sessionId &&
                    state is ConversationDictationState.Starting &&
                    restart.consume(scheduledRestartId)
            if (ownsScheduledRestart && finishRequested) {
                if (runCatching(platform::callerAudioHasPending).getOrDefault(false)) {
                    startRecognition(sessionId, target)
                } else {
                    finalizeAccumulatedTranscript(sessionId, target)
                }
            }
        },
    )
}

/** Keeps sealed PCM for Retry under bounded recovery protection after microphone closure. */
internal fun ConversationDictationController.failWithRetainedCallerAudio(
    sessionId: Long,
    target: ConversationDictationTarget,
    failure: ConversationDictationFailure,
    recognizedTranscript: String? = null,
) {
    if (state.sessionId != sessionId) return
    conversationDictationDiagnostic(
        "event=session_failed failure=${failure.name} retained_audio=true " +
            "retained_transcript=${accumulatedTranscript.isNotBlank()}",
    )
    recognitionDeadlines.cancelGeneration()
    recognitionDeadlines.clearSession()
    cancelPendingRestart()
    clearRecognitionGeneration(cancel = false)
    val drainAlreadyExpired = finishRequested && failure == ConversationDictationFailure.TimedOut
    finishRequested = true
    retainedAudioFailure = failure
    finishTiming.reset()
    val retained = recognizedTranscript ?: accumulatedTranscript.trim().ifBlank { null }
    // An explicit Send requests the whole recording, never automatic submission of a prefix.
    val recovery = recoverRecognizedDraftResult(sessionId, target, retained)
    if (state.sessionId != sessionId) return
    notifyBlockedSend(sessionId, retained)
    if (!recovery.draftRecovered && recovery != ConversationDictationDraftRecovery.Result.Superseded) {
        recoveryHandedToComposer = false
    }
    val failed =
        ConversationDictationState.Failed(
            sessionId = sessionId,
            target = target,
            reason =
                if (requestedDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish &&
                    !retained.isNullOrBlank()
                ) {
                    ConversationDictationFailure.SendBlocked
                } else {
                    failure
                },
            retainedTranscript = retained,
            cause =
                failure.takeIf {
                    requestedDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish &&
                        !retained.isNullOrBlank()
                },
            recognitionIncomplete = true,
            draftRecovered = recovery.draftRecovered,
            draftSuperseded = recovery == ConversationDictationDraftRecovery.Result.Superseded,
        )
    state = failed
    notificationActionGeneration += 1L
    protectRetainedRecovery(failed)
    closeRecoveryCapture(failed, drainAlreadyExpired)
}
