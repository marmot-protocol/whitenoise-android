package dev.ipf.whitenoise.android.audio

/** Releases recognition and microphone ownership, optionally retaining the durable service lease. */
internal fun ConversationDictationController.clearRecognitionSession(
    cancel: Boolean,
    releaseDurableSession: Boolean = true,
) {
    pendingForegroundRecoverySessionId = null
    completion.dispatch.cancelBeforeTransport()
    completion.validation.reset()
    recognitionDeadlines.clearSession()
    readinessHandle?.cancel()
    readinessHandle = null
    readinessStartedAtMillis = null
    // A cancelled probe must neither route this session nor record what it was about to learn.
    pendingCallerAudioProbeId = null
    foregroundLease.cancelPromotionTimeout()
    cancelPendingRestart()
    silenceTimeoutHandle?.cancel()
    silenceTimeoutHandle = null
    silenceDeadlineElapsedMillis = null
    val closingSessionId = state.sessionId
    val closingCaptureGeneration = captureClosureGeneration
    val releaseAfterCaptureClosure =
        releaseDurableSession && foregroundLease.active && foregroundMicrophoneRequired
    if (releaseAfterCaptureClosure) {
        // Dismissal can publish Idle, but the service must retain its promoted capture identity.
        foregroundLease.deferReleaseUntilCaptureClosed(closingCaptureGeneration, notificationSessionToken)
    }
    val captureSessionId = microphone.captureSessionId
    val onCaptureFinished: () -> Unit = {
        captureSessionId?.let(this::finishPlaybackInterruption)
        closingSessionId?.let { acknowledgeCaptureClosed(it, closingCaptureGeneration) }
    }
    val preserveAudio =
        retainedAudioFailure != null && runCatching(platform::callerAudioHasPending).getOrDefault(true)
    val platformOwnsCaptureClosure =
        runCatching {
            if (preserveAudio) {
                platform.finishCallerAudioCapture(onCaptureFinished)
            } else {
                platform.discardCallerAudio(onCaptureFinished)
            }
        }.getOrNull()
    // A disposal exception can release app audio ownership after recognizer teardown,
    // but cannot prove that a possibly separate native recorder has closed.
    val onGenerationClosed: () -> Unit =
        when (platformOwnsCaptureClosure) {
            false -> onCaptureFinished
            true -> ({})
            null -> ({ captureSessionId?.let(this::finishPlaybackInterruption) })
        }
    clearRecognitionGeneration(
        cancel = cancel,
        onAudioCaptureFinished = onGenerationClosed,
    )
    if (releaseDurableSession && !releaseAfterCaptureClosure) releaseDurableSessionLease()
}

/** Delivery, explicit dismissal and expiry end the logical foreground lease. */
internal fun ConversationDictationController.releaseDurableSessionLease() = foregroundLease.release()

/** Ends provider-owned microphone focus without racing White Noise-owned PCM capture. */
internal fun ConversationDictationController.finishProviderOwnedCapture(sessionId: Long) {
    // White Noise-owned PCM remains paused until its recorder reports physical closure.
    val callerOwnsCapture =
        runCatching { generation.session?.usesCallerAudioCapture() ?: true }.getOrDefault(true)
    if (!callerOwnsCapture) finishPlaybackInterruption(sessionId)
}

/** Restores only playback that this recognition session interrupted, at most once. */
internal fun ConversationDictationController.finishPlaybackInterruption(sessionId: Long) = microphone.finish(sessionId)

/** Tears down one recognizer generation and optionally acknowledges physical capture closure. */
internal fun ConversationDictationController.clearRecognitionGeneration(
    cancel: Boolean,
    onAudioCaptureFinished: () -> Unit = {},
) {
    val providerEnded = generation.providerEnded
    val session = generation.retire()
    if (session == null) {
        onAudioCaptureFinished()
        return
    }
    val callerAudio = runCatching(session::usesCallerAudioCapture).getOrDefault(false)
    if (cancel) {
        runCatching { session.cancel(onAudioCaptureFinished) }
            .onFailure { onAudioCaptureFinished() }
    }
    runCatching { session.destroy(onAudioCaptureFinished) }
        .onFailure { onAudioCaptureFinished() }
    restart.noteTeardown(
        elapsedRealtime(),
        providerEndedSession = state.sessionId.takeIf { !cancel && providerEnded && callerAudio },
    )
}
