package dev.ipf.whitenoise.android.audio

/** Rejects callbacks from both superseded logical sessions and destroyed recognizer generations. */
internal fun ConversationDictationController.owns(
    sessionId: Long,
    generationId: Long,
    callback: Boolean = false,
): Boolean {
    val accepted =
        state.sessionId == sessionId && generation.activeId == generationId && generation.session != null
    if (!accepted && callback) {
        conversationDictationDiagnostic(
            "event=callback_rejected callback_session=$sessionId generation=$generationId reason=stale_request",
        )
    }
    return accepted
}

/** Publishes a failure after releasing recognition, retaining foreground protection for recovery data. */
@Suppress("ReturnCount")
internal fun ConversationDictationController.fail(
    sessionId: Long,
    target: ConversationDictationTarget,
    reason: ConversationDictationFailure,
    captureEnd: DictationFailureCapture = DictationFailureCapture.Complete,
    retainedTranscript: String? = null,
    attemptRecovery: Boolean = true,
) {
    if (state.sessionId != sessionId) return
    val failure = completedTranscriptFailure(reason, retainedTranscript)
    conversationDictationDiagnostic("event=session_failed failure=${failure.name}")
    val recovery =
        if (attemptRecovery) {
            recoverRecognizedDraftResult(sessionId, target, retainedTranscript)
        } else {
            ConversationDictationDraftRecovery.Result.Unavailable
        }
    if (state.sessionId != sessionId) return
    notifyBlockedSend(sessionId, retainedTranscript, failure)
    val recoverable =
        failure != ConversationDictationFailure.DeliveryUnknown && !retainedTranscript.isNullOrBlank()
    clearRecognitionSession(
        cancel = captureEnd != DictationFailureCapture.Complete,
        releaseDurableSession = !recoverable,
    )
    if (state.sessionId != sessionId) return
    val retainsAudio =
        retainedAudioFailure != null && runCatching(platform::callerAudioHasPending).getOrDefault(true)
    if (!retainsAudio && failure != ConversationDictationFailure.DraftWriteDeferred) {
        val mode = requestedDeliveryMode
        val explicit = completion.intent.explicit
        resetTranscriptSession()
        completion.intent.choose(mode, automatic = !explicit)
    }
    state =
        ConversationDictationState.Failed(
            sessionId,
            target,
            failure,
            retainedTranscript,
            cause =
                if (!attemptRecovery &&
                    reason == ConversationDictationFailure.Unknown
                ) {
                    ConversationDictationFailure.DraftWriteRefused
                } else {
                    reason.takeIf { failure != it }
                },
            recognitionIncomplete = captureEnd == DictationFailureCapture.CancelIncomplete,
            draftRecovered = recovery.draftRecovered,
            draftSuperseded = recovery == ConversationDictationDraftRecovery.Result.Superseded,
        )
    notificationActionGeneration += 1L
    val failed = state as ConversationDictationState.Failed
    handRecoveryToComposer(failed)
    protectFailedTranscript(retainedTranscript, failure)
}

/** Protects useful failure text after recognition cleanup and composer handoff. */
internal fun ConversationDictationController.protectFailedTranscript(
    retainedTranscript: String?,
    failure: ConversationDictationFailure,
) {
    (state as? ConversationDictationState.Failed)
        ?.takeIf { !retainedTranscript.isNullOrBlank() && failure != ConversationDictationFailure.DeliveryUnknown }
        ?.let { failed ->
            protectRetainedRecovery(failed)
            if (failed.draftSuperseded &&
                !foregroundMicrophoneRequired &&
                !runCatching(platform::callerAudioHasPending).getOrDefault(true)
            ) {
                releaseDurableSessionLease()
            }
            if (foregroundMicrophoneRequired) {
                closeRecoveryCapture(failed, drainAlreadyExpired = false, captureAlreadyDiscarded = true)
            }
        }
}

/** One explicit Send gesture produces at most one notice for useful text held back from transport. */
internal fun ConversationDictationController.notifyBlockedSend(
    sessionId: Long,
    transcript: String?,
    failure: ConversationDictationFailure? = null,
) {
    if (failure == ConversationDictationFailure.DeliveryUnknown) return
    if (!explicitSendRequested ||
        transcript.isNullOrBlank() ||
        !completion.dispatch.shouldNotifyBlocked(sessionId)
    ) {
        return
    }
    runCatching(onSendBlocked)
}

/** Send failures retain the explicit send choice while uncertain delivery keeps its separate warning. */
internal fun ConversationDictationController.completedTranscriptFailure(
    reason: ConversationDictationFailure,
    retainedTranscript: String?,
): ConversationDictationFailure =
    if (!retainedTranscript.isNullOrBlank() &&
        requestedDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish &&
        reason != ConversationDictationFailure.DeliveryUnknown
    ) {
        ConversationDictationFailure.SendBlocked
    } else {
        reason
    }
