package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.CALLER_AUDIO_DRAIN_TIMEOUT_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.PROCESSING_TIMEOUT_MILLIS

/** Preserves origin validation while the user explicitly chooses what to do with recovered text. */
internal fun ConversationDictationController.deliverRetainedTranscript(
    failed: ConversationDictationState.Failed,
    mode: ConversationDictationDeliveryMode,
) {
    if (mode == ConversationDictationDeliveryMode.SendOnFinish) completion.dispatch.resetBlockedNotice()
    recoveryTimeoutHandle?.cancel()
    recoveryTimeoutHandle = null
    recoveryDeadlineElapsedMillis = null
    recoveryProtectionExpired = false
    pendingForegroundRecoverySessionId = null
    notificationActionGeneration += 1L
    completion.intent.reset()
    completion.intent.choose(mode)
    finishRequested = true
    state = ConversationDictationState.Processing(failed.sessionId, failed.target)
    validateAndDeliverTranscript(failed.sessionId, failed.target, requireNotNull(failed.retainedTranscript))
}

/** Completes the owned session with its captured paste-or-send policy. */
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
internal fun ConversationDictationController.stopWithDeliveryMode(
    deliveryMode: ConversationDictationDeliveryMode?,
    automatic: Boolean = false,
) {
    if (followUpTakesCompletion(deliveryMode, automatic)) return
    expireRetainedRecoveryIfDue()
    val captureGeneration = captureClosureGeneration
    val current = state
    val action = deliveryMode?.name ?: "Done"
    val source = if (automatic) "automatic" else "explicit"
    val previousMode = requestedDeliveryMode ?: ConversationDictationDeliveryMode.PasteIntoDraft
    val nextMode = deliveryMode ?: ConversationDictationDeliveryMode.PasteIntoDraft
    val replacingValidation =
        previousMode != nextMode &&
            current.sessionId?.let(completion.validation::validationInProgress) == true
    if (!inActiveRecognitionState ||
        !completion.intent.choose(deliveryMode, automatic)
    ) {
        conversationDictationDiagnostic(
            "event=completion_action action=$action source=$source accepted=false reason=state",
        )
        return
    }
    // A result for the old choice must not deliver or reject the replacement choice.
    if (replacingValidation) completion.validation.reset()
    conversationDictationDiagnostic("event=completion_action action=$action source=$source accepted=true")
    finishTiming.tap(elapsedRealtime())
    // Capture is already sealed by the silence endpoint; only the delivery choice changes.
    if (finishRequested) {
        if (replacingValidation && completion.transcript.isNotBlank()) {
            // Re-evaluate the latest policy against the same completed transcript.
            // Paste remains local; a replacement Send gets a fresh validation token.
            validateAndDeliverTranscript(
                requireNotNull(current.sessionId),
                requireNotNull(current.target),
                completion.transcript,
            )
        }
        return
    }
    if (platform.callerAudioHasPending()) {
        armCallerAudioDrainTimeout(requireNotNull(current.sessionId), requireNotNull(current.target))
    }
    if (foregroundLease.active && !foregroundLease.ready) {
        finishRequested = true
        finalizeAccumulatedTranscript(requireNotNull(current.sessionId), requireNotNull(current.target))
        return
    }
    if (current is ConversationDictationState.Processing) {
        finishRequested = true
        silenceTimeoutHandle?.cancel()
        silenceTimeoutHandle = null
        val sessionId = current.sessionId
        val target = current.target
        val generationId = generation.activeId
        if (generationId == null) {
            sealAndDrainCallerAudio(sessionId, target)
        } else {
            runCatching {
                generation.session?.stop {
                    acknowledgeCaptureClosed(sessionId, captureGeneration)
                    if (owns(sessionId, generationId)) {
                        finishPlaybackInterruption(sessionId)
                    }
                }
            }.onFailure {
                failOrRetainTranscript(sessionId, target, ConversationDictationFailure.Unknown)
            }
        }
        return
    }
    if (current !is ConversationDictationState.Starting && current !is ConversationDictationState.Listening) return
    val sessionId = current.sessionId ?: return
    val target = current.target ?: return
    finishRequested = true
    cancelPendingRestart()
    silenceTimeoutHandle?.cancel()
    silenceTimeoutHandle = null
    silenceDeadlineElapsedMillis = null
    val generationId = generation.activeId
    if (generationId == null) {
        sealAndDrainCallerAudio(sessionId, target)
        return
    }
    if (
        accumulatedTranscript.isNotBlank() &&
        !generation.hasSpeech &&
        !platform.callerAudioHasPending()
    ) {
        clearRecognitionGeneration(
            cancel = true,
            onAudioCaptureFinished = { finishPlaybackInterruption(sessionId) },
        )
        finalizeAccumulatedTranscript(sessionId, target)
        return
    }
    state = ConversationDictationState.Processing(sessionId, target)
    armGenerationTimeout(
        sessionId,
        generationId,
        PROCESSING_TIMEOUT_MILLIS,
        ConversationDictationRecognitionDeadlines.GenerationPhase.Processing,
    ) {
        failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
    }
    runCatching {
        generation.session?.stop {
            acknowledgeCaptureClosed(sessionId, captureGeneration)
            if (owns(sessionId, generationId)) {
                finishPlaybackInterruption(sessionId)
            }
        }
    }.onFailure { failOrRetainTranscript(sessionId, target, ConversationDictationFailure.Unknown) }
}

/** Seals a caller-owned partial chunk and starts one final provider generation to drain it. */
internal fun ConversationDictationController.sealAndDrainCallerAudio(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    val captureGeneration = captureClosureGeneration
    state = ConversationDictationState.Processing(sessionId, target)
    armCallerAudioDrainTimeout(sessionId, target)
    val platformOwnsClosure =
        runCatching {
            platform.finishCallerAudioCapture {
                acknowledgeCaptureClosed(sessionId, captureGeneration)
                if (state !is ConversationDictationState.Processing || state.sessionId != sessionId) {
                    return@finishCallerAudioCapture
                }
                finishPlaybackInterruption(sessionId)
                if (platform.callerAudioHasPending()) {
                    startRecognition(sessionId, target)
                } else {
                    continueOrFinalizeCallerAudioDrain(sessionId, target)
                }
            }
        }.getOrDefault(false)
    if (!platformOwnsClosure) {
        finishPlaybackInterruption(sessionId)
        continueOrFinalizeCallerAudioDrain(sessionId, target)
    }
}

/** Keeps one drain deadline active while replacement recognizers move through their states. */
internal fun ConversationDictationController.armCallerAudioDrainTimeout(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    recognitionDeadlines.beginCallerAudioDrain(sessionId) {
        diagnosticTimeout(
            sessionId,
            CALLER_AUDIO_DRAIN_TIMEOUT_MILLIS,
            "caller_audio_drain",
            isCurrent = { finishRequested && state.sessionId == sessionId },
        ) {
            failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
        }
    }
}
