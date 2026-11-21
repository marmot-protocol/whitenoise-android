package dev.ipf.whitenoise.android.audio

/** Only native closure acknowledgment may narrow microphone ownership and disarm its watchdog. */
internal fun ConversationDictationController.acknowledgeCaptureClosed(
    sessionId: Long,
    captureGeneration: Long,
) {
    if (captureGeneration != captureClosureGeneration) return
    val releasesTerminalLease = foregroundLease.deferredReleaseCaptureGeneration == captureGeneration
    if (state.sessionId != sessionId && !releasesTerminalLease) return
    finishTiming.captureClosed(elapsedRealtime())
    foregroundMicrophoneRequired = false
    captureClosureTimeoutHandle?.cancel()
    captureClosureTimeoutHandle = null
    foregroundLease.releaseAfterCaptureClosed(captureGeneration)
    val failed = state as? ConversationDictationState.Failed
    val recoveryPresented =
        failed?.let { pendingForegroundRecoverySessionId == sessionId || it.draftSuperseded } == true
    if (recoveryPresented &&
        !hasUnrecoveredTranscript &&
        !runCatching(platform::callerAudioHasPending).getOrDefault(true)
    ) {
        releaseDurableSessionLease()
    }
    if (recoveryHandedToComposer) (state as? ConversationDictationState.Failed)?.let(this::handRecoveryToComposer)
    releaseExpiredRecoveryProtection()
    reattachForegroundRecoveryAfterClosure()
    armFollowUpCapture()
}

/** Retries only the captured deferred draft; competing edits remain intact. */
internal fun ConversationDictationController.retryForegroundDeferredDraft(failed: ConversationDictationState.Failed) {
    val transcript = failed.retainedTranscript ?: return
    when (completion.retryDeferred(failed.sessionId, failed.target)) {
        ConversationDictationCompletionOwner.PasteResult.Accepted -> complete(failed.target)
        ConversationDictationCompletionOwner.PasteResult.Retained ->
            fail(
                failed.sessionId,
                failed.target,
                ConversationDictationFailure.Unknown,
                retainedTranscript = transcript,
                attemptRecovery = false,
            )
        ConversationDictationCompletionOwner.PasteResult.Superseded ->
            state =
                failed.copy(
                    reason = ConversationDictationFailure.Unknown,
                    cause = ConversationDictationFailure.DraftWriteRefused,
                    draftSuperseded = true,
                )
        ConversationDictationCompletionOwner.PasteResult.DeferredByHost,
        ConversationDictationCompletionOwner.PasteResult.Abandoned,
        -> Unit
    }
}

/** A foreground return cannot substitute for native recorder closure or disarm its watchdog. */
@Suppress("ReturnCount")
internal fun ConversationDictationController.reattachForegroundRecoveryAfterClosure() {
    expireRetainedRecoveryIfDue()
    val failed = state as? ConversationDictationState.Failed ?: return
    if (recoveryProtectionExpired) return
    val hasRecoveryData =
        hasUnrecoveredTranscript ||
            runCatching(platform::callerAudioHasPending).getOrDefault(false)
    if (failed.reason == ConversationDictationFailure.DeliveryUnknown || !hasRecoveryData) {
        pendingForegroundRecoverySessionId = null
        return
    }
    if (pendingForegroundRecoverySessionId != failed.sessionId ||
        foregroundLease.active ||
        foregroundMicrophoneRequired
    ) {
        return
    }
    pendingForegroundRecoverySessionId = null
    ensureDurableSession(failed.sessionId, failed.target)
}

/** A sealed-audio retry needs its provider, but never requests or reacquires microphone capture. */
internal fun ConversationDictationController.acknowledgeSealedAudioReady(
    current: ConversationDictationState.Starting,
    sessionToken: String,
) {
    val available = platform.recognitionAvailable()
    if (!ownsDurableSession(current.sessionId, sessionToken)) return
    if (available) {
        acknowledgeDurableServiceReady(current.sessionId, current.target)
    } else {
        failWithRetainedCallerAudio(
            current.sessionId,
            current.target,
            ConversationDictationFailure.ProviderUnavailable,
        )
    }
}

internal fun ConversationDictationController.acknowledgeDurableServiceReady(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    foregroundLease.markReady()
    if (!finishRequested || !restart.pending) startRecognition(sessionId, target)
}

internal fun ConversationDictationController.ownsDurableSession(
    sessionId: Long,
    sessionToken: String,
): Boolean =
    foregroundLease.active &&
        state.sessionId == sessionId &&
        notificationSessionToken == sessionToken
