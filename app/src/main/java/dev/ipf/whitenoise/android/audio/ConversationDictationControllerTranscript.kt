package dev.ipf.whitenoise.android.audio

/** Appends one provider-final segment; generation ownership rejects duplicate callbacks. */
internal fun ConversationDictationController.commitSegment(segment: String) {
    val normalized = segment.trim()
    if (normalized.isBlank()) return
    recognition.acceptProviderMicrophone(normalized)
    resetAcceptedRecognitionState()
}

internal fun ConversationDictationController.resetAcceptedRecognitionState() {
    restart.resetRapidNoSpeech()
    recognition.resetFailure()
    silenceDeadlineElapsedMillis = null
}

/** Runs the accumulated transcript through authoritative target validation and the captured delivery policy. */
internal fun ConversationDictationController.finalizeAccumulatedTranscript(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    val transcript = accumulatedTranscript.trim()
    when {
        runCatching(platform::callerAudioHasPending).getOrDefault(true) ->
            failWithRetainedCallerAudio(
                sessionId,
                target,
                unresolvedRecognitionFailure ?: ConversationDictationFailure.NoSpeech,
            )
        unresolvedRecognitionFailure?.shouldPreserveTranscript == true ->
            failOrRetainTranscript(sessionId, target, requireNotNull(unresolvedRecognitionFailure))
        transcript.isBlank() ->
            fail(sessionId, target, unresolvedRecognitionFailure ?: ConversationDictationFailure.NoSpeech)
        else -> {
            recognitionDeadlines.finishCallerAudioDrain(sessionId)
            validateAndDeliverTranscript(sessionId, target, transcript)
        }
    }
}

/** Releases capture but preserves useful text when a later generation fails fatally. */
internal fun ConversationDictationController.preserveAccumulatedTranscriptInLatestDraft(
    sessionId: Long,
    target: ConversationDictationTarget,
) = preserveTranscriptInLatestDraft(sessionId, target, accumulatedTranscript, unresolvedRecognitionFailure)

/** Preserves useful text automatically in the latest draft instead of exposing a second edit action. */
internal fun ConversationDictationController.preserveTranscriptInLatestDraft(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String,
    failureCause: ConversationDictationFailure? = null,
) {
    if (transcript.isBlank()) {
        fail(sessionId, target, ConversationDictationFailure.NoSpeech, captureEnd = DictationFailureCapture.Cancel)
    } else if (requestedDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish) {
        fail(
            sessionId,
            target,
            failureCause ?: ConversationDictationFailure.SendBlocked,
            captureEnd =
                if (failureCause == null) {
                    DictationFailureCapture.Cancel
                } else {
                    DictationFailureCapture.CancelIncomplete
                },
            retainedTranscript = transcript,
        )
    } else if (completion.draftRecovery.owns(sessionId, target)) {
        preserveRecoveredTranscript(sessionId, target, transcript, failureCause)
    } else {
        clearRecognitionSession(cancel = true)
        when (
            completion.appendLatest(
                ConversationDictationCompletionOwner.DraftOperation(
                    sessionId = sessionId,
                    target = target,
                    text = transcript,
                    attempts = DICTATION_DRAFT_WRITE_ATTEMPTS,
                    currentSessionId = { state.sessionId },
                    validate = { completedTargetValidation(target) },
                ),
            )
        ) {
            ConversationDictationCompletionOwner.PasteResult.Accepted -> complete(target)
            ConversationDictationCompletionOwner.PasteResult.Retained ->
                fail(
                    sessionId,
                    target,
                    failureCause ?: ConversationDictationFailure.Unknown,
                    retainedTranscript = transcript,
                )
            ConversationDictationCompletionOwner.PasteResult.DeferredByHost ->
                fail(
                    sessionId,
                    target,
                    ConversationDictationFailure.DraftWriteDeferred,
                    retainedTranscript = transcript,
                    attemptRecovery = false,
                )
            ConversationDictationCompletionOwner.PasteResult.Superseded,
            ConversationDictationCompletionOwner.PasteResult.Abandoned,
            -> Unit
        }
    }
}

/** User edits settle saved text only after any unrecognized PCM has been retained separately. */
internal fun ConversationDictationController.preserveRecoveredTranscript(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String,
    failureCause: ConversationDictationFailure?,
) {
    val failure = failureCause ?: ConversationDictationFailure.Unknown
    if (runCatching(platform::callerAudioHasPending).getOrDefault(true)) {
        failWithRetainedCallerAudio(sessionId, target, failure, recognizedTranscript = transcript)
    } else {
        clearRecognitionSession(cancel = true, releaseDurableSession = false)
        completeRecoveredPaste(sessionId, target, transcript, failure)
    }
}

/** Preserves useful dictated text when a recognition watchdog or provider operation fails. */
internal fun ConversationDictationController.failOrRetainTranscript(
    sessionId: Long,
    target: ConversationDictationTarget,
    reason: ConversationDictationFailure,
) {
    val retainedAudio = runCatching(platform::callerAudioHasPending).getOrDefault(false)
    conversationDictationDiagnostic(
        "event=failure_recovery failure=${reason.name} has_text=${accumulatedTranscript.isNotBlank()} " +
            "retained_audio=$retainedAudio",
    )
    if (retainedAudio) {
        failWithRetainedCallerAudio(sessionId, target, reason)
    } else if (accumulatedTranscript.isNotBlank()) {
        preserveTranscriptInLatestDraft(sessionId, target, accumulatedTranscript, failureCause = reason)
    } else {
        fail(sessionId, target, reason, captureEnd = DictationFailureCapture.Cancel)
    }
}

/** Clears all process-memory transcript state after terminal delivery, discard, or failure. */
internal fun ConversationDictationController.resetTranscriptSession() {
    recoveryTimeoutHandle?.cancel()
    recoveryTimeoutHandle = null
    recoveryDeadlineElapsedMillis = null
    recoveryProtectionExpired = false
    recognition.reset()
    recoveryHandedToComposer = false
    finishRequested = false
    captureDeadlineElapsedMillis = null
    completion.reset()
    generation.resetPolicy()
    restart.resetPolicy()
    recognition.resetFailure()
    unresolvedRecognitionFailure = null
    silenceDeadlineElapsedMillis = null
}
