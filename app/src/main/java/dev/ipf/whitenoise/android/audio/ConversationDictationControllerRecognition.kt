package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.GENERATION_RESTART_DELAY_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.MAX_RETAINED_CALLER_AUDIO_RETRIES
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.MAX_SESSION_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.PROCESSING_TIMEOUT_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.STARTING_TIMEOUT_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.SUCCESS_RESULT_RESTART_DELAY_MILLIS

// One generation's start is a single ownership-checked sequence.

/** Starts one bounded recognizer generation while retaining logical-session ownership. */
@Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
internal fun ConversationDictationController.startRecognition(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    if (state.sessionId != sessionId) return
    if (!finishRequested && captureDeadlineReached()) {
        failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
        return
    }
    clearRecognitionGeneration(cancel = false)
    if (!ensureDurableSession(sessionId, target)) return
    if (deferRecognitionUntilTeardownSettles(sessionId, target)) return
    if (microphone.heldByOtherSession(sessionId)) {
        // A replaced session's asynchronous recorder close has not returned yet.
        // Never reuse its microphone or focus lease for a different target.
        fail(sessionId, target, ConversationDictationFailure.MicrophoneInUse)
        return
    }
    // Drain generations use sealed PCM; reacquiring capture would replace their drain deadline.
    if (!microphone.held && !finishRequested) {
        if (!microphone.acquire(sessionId)) {
            fail(sessionId, target, ConversationDictationFailure.MicrophoneInUse)
            return
        }
        val preparationFailure = runCatching { onBeforeRecognition(target) }.exceptionOrNull()
        if (preparationFailure != null) {
            fail(
                sessionId,
                target,
                if (preparationFailure is ConversationDictationAudioFocusDenied) {
                    ConversationDictationFailure.AudioFocusUnavailable
                } else {
                    ConversationDictationFailure.Unknown
                },
            )
            return
        }
        val captureDeadline =
            captureDeadlineElapsedMillis ?: (elapsedRealtime() + MAX_SESSION_MILLIS).also {
                captureDeadlineElapsedMillis = it
            }
        armSessionTimeout(sessionId, (captureDeadline - elapsedRealtime()).coerceAtLeast(0L), "capture") {
            when (state) {
                is ConversationDictationState.Starting,
                is ConversationDictationState.Listening,
                is ConversationDictationState.Processing,
                -> stop()
                else -> failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
            }
        }
    }
    val generationId = generation.begin()
    val generationStartedAtElapsedMillis = elapsedRealtime()
    conversationDictationDiagnostic("event=recognizer_generation_start generation=$generationId")
    state = ConversationDictationState.Starting(sessionId, target)
    armGenerationTimeout(
        sessionId,
        generationId,
        STARTING_TIMEOUT_MILLIS,
        ConversationDictationRecognitionDeadlines.GenerationPhase.Startup,
    ) {
        conversationDictationDiagnostic(
            "event=recognizer_start_timeout generation=$generationId " +
                "elapsed_ms=${elapsedRealtime() - generationStartedAtElapsedMillis}",
        )
        failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
    }
    val listener =
        object : ConversationDictationRecognitionListener {
            /** Promotes only the current generation from starting to listening. */
            override fun onReady() {
                conversationDictationDiagnostic(
                    "event=callback_ready generation=$generationId " +
                        "elapsed_ms=${elapsedRealtime() - generationStartedAtElapsedMillis}",
                )
                if (!owns(sessionId, generationId, callback = true)) return
                if (state is ConversationDictationState.Processing && recognitionDeadlines.isDraining(sessionId)) {
                    // Send can seal capture before provider readiness; keep draining after its acknowledgment.
                    unresolvedRecognitionFailure = null
                    generation.ready(elapsedRealtime())
                    return
                }
                if (state !is ConversationDictationState.Starting) {
                    return
                }
                unresolvedRecognitionFailure = null
                val readyAt = generation.ready(elapsedRealtime())
                state =
                    ConversationDictationState.Listening(
                        sessionId = sessionId,
                        target = target,
                        startedAtElapsedMillis = readyAt,
                    )
            }

            /** Records speech only for the generation that still owns the session. */
            override fun onBeginningOfSpeech() {
                conversationDictationDiagnostic("event=callback_beginning_of_speech generation=$generationId")
                if (!owns(sessionId, generationId, callback = true)) return
                generation.markSpeech()
                unresolvedRecognitionFailure = null
                if (generation.callerAudioEndpointingActive) {
                    // Provider callbacks can lag White Noise-owned capture by an entire chunk.
                    // Keep endpointing tied to current PCM activity instead of disarming it here.
                    armSilenceDeadline(sessionId, target)
                } else {
                    silenceTimeoutHandle?.cancel()
                    silenceTimeoutHandle = null
                    silenceDeadlineElapsedMillis = null
                }
            }

            /** Moves the owned generation into bounded final-result processing. */
            override fun onEndOfSpeech() {
                conversationDictationDiagnostic("event=callback_end_of_speech generation=$generationId")
                if (!owns(sessionId, generationId, callback = true)) return
                finishProviderOwnedCapture(sessionId)
                when {
                    state is ConversationDictationState.Starting ||
                        state is ConversationDictationState.Listening -> {
                        state = ConversationDictationState.Processing(sessionId, target)
                        armGenerationTimeout(
                            sessionId,
                            generationId,
                            PROCESSING_TIMEOUT_MILLIS,
                            ConversationDictationRecognitionDeadlines.GenerationPhase.Processing,
                        ) {
                            failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
                        }
                    }
                }
            }

            /** Commits an owned final segment and either finishes or schedules the next generation. */
            override fun onResult(transcript: String?) {
                conversationDictationDiagnostic(
                    "event=callback_result generation=$generationId has_text=${!transcript.isNullOrBlank()} " +
                        "elapsed_ms=${elapsedRealtime() - generationStartedAtElapsedMillis}",
                )
                if (!owns(sessionId, generationId, callback = true)) return
                // Some providers skip onEndOfSpeech. A terminal result still ends their
                // microphone capture before the next generation or transcript delivery.
                finishProviderOwnedCapture(sessionId)
                if (!runCatching(platform::pinnedProviderStillAvailable).getOrDefault(false)) {
                    failOrRetainTranscript(sessionId, target, ConversationDictationFailure.ProviderUnavailable)
                    return
                }
                val recognized = transcript?.trim().orEmpty()
                if (recognized.isNotBlank()) unresolvedRecognitionFailure = null
                if (recognized.isBlank()) {
                    onError(ConversationDictationFailure.NoSpeech)
                    return
                }
                val currentSession = generation.session
                val request = currentSession?.callerAudioRequest()
                if (request != null && !recognition.canAccept(request)) {
                    failWithRetainedCallerAudio(sessionId, target, ConversationDictationFailure.Unknown)
                    return
                }
                val settlement =
                    if (request != null) currentSession.acknowledgeCallerAudio(recognized) else null
                if (currentSession?.usesCallerAudioCapture() == true &&
                    if (request != null) settlement == null else currentSession.acknowledgeCallerAudio() != true
                ) {
                    failWithRetainedCallerAudio(
                        sessionId,
                        target,
                        ConversationDictationFailure.Unknown,
                        recognition.provisionalTranscript(request, recognized),
                    )
                    return
                }
                clearRecognitionGeneration(cancel = false)
                if (settlement != null) {
                    if (!recognition.accept(settlement)) {
                        failWithRetainedCallerAudio(sessionId, target, ConversationDictationFailure.Unknown)
                        return
                    }
                    resetAcceptedRecognitionState()
                } else {
                    commitSegment(recognized)
                }
                val targetStillAvailable = runCatching { targetAvailable(target) }.getOrDefault(false)
                if (!targetStillAvailable) {
                    fail(
                        sessionId,
                        target,
                        ConversationDictationFailure.Unknown,
                        captureEnd = DictationFailureCapture.Cancel,
                        retainedTranscript = accumulatedTranscript.trim(),
                    )
                    return
                }
                if (finishRequested) {
                    continueOrFinalizeCallerAudioDrain(sessionId, target)
                } else {
                    scheduleRestart(sessionId, target, SUCCESS_RESULT_RESTART_DELAY_MILLIS, "result")
                }
            }

            /** A dead recorder requires explicit sealed-audio recovery, without acquiring another microphone. */
            override fun onCallerAudioFailure(
                failure: ConversationDictationCallerAudioFailure,
                acknowledge: () -> Boolean,
            ) {
                if (!owns(sessionId, generationId, callback = true) || !acknowledge()) return
                if (failure == ConversationDictationCallerAudioFailure.CaptureFailed) {
                    failOrRetainTranscript(sessionId, target, failure.recognitionFailure)
                } else {
                    onError(failure.recognitionFailure)
                }
            }

            /** Applies retry or terminal-failure policy only to the current generation. */
            override fun onError(error: ConversationDictationFailure) {
                conversationDictationDiagnostic(
                    "event=callback_error generation=$generationId failure=${error.name} " +
                        "elapsed_ms=${elapsedRealtime() - generationStartedAtElapsedMillis}",
                )
                if (!owns(sessionId, generationId, callback = true)) return
                finishProviderOwnedCapture(sessionId)
                val readyAt = generation.readyAtElapsedMillis
                val failure =
                    if (error == ConversationDictationFailure.PermissionDenied) {
                        recognitionPermissionFailure()
                    } else if (error.isEmptyRecognition) {
                        unresolvedRecognitionFailure ?: error
                    } else {
                        error
                    }
                if (!owns(sessionId, generationId)) return
                unresolvedRecognitionFailure = failure
                val currentSession = generation.session
                if (error.isEmptyRecognition && dropSilentFinalTail(sessionId, target, currentSession, failure)) {
                    return
                }
                val callerAudioPending =
                    currentSession?.usesCallerAudioCapture() == true ||
                        runCatching(platform::callerAudioHasPending).getOrDefault(false)
                val resolution =
                    recognition.resolveFailure(
                        ConversationDictationRecognitionCoordinator.FailureInput(
                            session = currentSession,
                            empty = error.isEmptyRecognition,
                            callerAudioPending = callerAudioPending,
                            countInputRetry =
                                (finishRequested || error.isEmptyRecognition) &&
                                    failure.canRetryRetainedCallerAudio,
                            capacityFailure = failure.hasProviderCapacityBackoff,
                            maxRetries = MAX_RETAINED_CALLER_AUDIO_RETRIES,
                        ),
                    )
                if (resolution ==
                    ConversationDictationRecognitionCoordinator.FailureResolution.ConfirmedDigitalSilence
                ) {
                    if (unresolvedRecognitionFailure?.isEmptyRecognition == true) {
                        unresolvedRecognitionFailure = null
                    }
                    clearRecognitionGeneration(cancel = false)
                    advancePastConfirmedSilentCallerAudio(sessionId, target)
                    return
                }
                if (resolution == ConversationDictationRecognitionCoordinator.FailureResolution.Exhausted) {
                    conversationDictationDiagnostic(
                        "event=caller_audio_retry_exhausted failure=${failure.name} " +
                            "attempts=${recognition.failureAttempts} action=retain",
                    )
                    failWithRetainedCallerAudio(sessionId, target, failure)
                    return
                }
                val retainedCallerAudio =
                    (resolution as ConversationDictationRecognitionCoordinator.FailureResolution.Retained).requeued
                val stillPending =
                    retainedCallerAudio || runCatching(platform::callerAudioHasPending).getOrDefault(false)
                if (finishRequested) {
                    if (stillPending && failure.canRetryRetainedCallerAudio) {
                        clearRecognitionGeneration(cancel = false)
                        retryRetainedCallerAudioOrFail(sessionId, target, failure)
                    } else {
                        failOrRetainTranscript(sessionId, target, failure)
                    }
                    return
                }
                if (error.isEmptyRecognition ||
                    error == ConversationDictationFailure.PermissionDenied ||
                    error == ConversationDictationFailure.ProviderDisconnected
                ) {
                    clearRecognitionGeneration(cancel = false)
                }
                when {
                    error.isEmptyRecognition -> restartAfterNoSpeech(sessionId, target, readyAt)
                    error == ConversationDictationFailure.PermissionDenied ->
                        recoverPermissionFailure(sessionId, target)
                    error == ConversationDictationFailure.ProviderDisconnected ->
                        recoverProviderDisconnect(sessionId, target)
                    else -> failOrRetainTranscript(sessionId, target, failure)
                }
            }
        }
    runCatching {
        val session = platform.createSession(listener).also(generation::attach)
        session.start()
        if (owns(sessionId, generationId) && session.usesCallerAudioCapture()) {
            generation.markCallerAudioEndpointing()
            armSilenceDeadline(sessionId, target)
        }
    }.onFailure { error ->
        conversationDictationDiagnostic("event=recognizer_start_exception type=${error.javaClass.simpleName}")
        failOrRetainTranscript(
            sessionId,
            target,
            if (error is ConversationDictationProviderUnavailableException) {
                ConversationDictationFailure.ProviderUnavailable
            } else {
                ConversationDictationFailure.Unknown
            },
        )
    }
}

/** Lets Android finish unbinding before any logical session creates a replacement recognizer. */
internal fun ConversationDictationController.deferRecognitionUntilTeardownSettles(
    sessionId: Long,
    target: ConversationDictationTarget,
): Boolean {
    val releasedAt = restart.lastTeardownAtElapsedMillis ?: (elapsedRealtime() - GENERATION_RESTART_DELAY_MILLIS)
    val delayMillis = (GENERATION_RESTART_DELAY_MILLIS - (elapsedRealtime() - releasedAt)).coerceAtLeast(0L)
    if (delayMillis == 0L) return false
    cancelPendingRestart()
    state = ConversationDictationState.Starting(sessionId, target)
    val scheduledRestartId = restart.reserve()
    conversationDictationDiagnostic("event=recognizer_restart_scheduled reason=teardown delay_ms=$delayMillis")
    restart.install(
        scheduledRestartId,
        scheduleTimeout(delayMillis) {
            if (
                state.sessionId == sessionId &&
                state is ConversationDictationState.Starting &&
                restart.consume(scheduledRestartId)
            ) {
                if (finishRequested) {
                    if (runCatching(platform::callerAudioHasPending).getOrDefault(false)) {
                        startRecognition(sessionId, target)
                    } else {
                        finalizeAccumulatedTranscript(sessionId, target)
                    }
                } else {
                    resumeRecognitionAfterDelay(sessionId, target)
                }
            }
        },
    )
    return true
}

/** Advances after acknowledging one fully supplied, digitally silent chunk. */
internal fun ConversationDictationController.advancePastConfirmedSilentCallerAudio(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    conversationDictationDiagnostic("event=caller_audio_silence_acknowledged action=advance")
    recognition.resetFailure()
    when {
        runCatching(platform::callerAudioHasPending).getOrDefault(false) ->
            startRecognition(sessionId, target)
        finishRequested -> finalizeAccumulatedTranscript(sessionId, target)
        else -> scheduleRestart(sessionId, target, GENERATION_RESTART_DELAY_MILLIS, "no_speech_advanced")
    }
}

/**
 * Releases a capture-side silent final chunk after Send or Paste when recognized text already exists.
 *
 * Silence is decided from the captured PCM, so this does not wait for the provider to be fully fed.
 * A final chunk that may hold speech keeps the bounded retry and retained-audio recovery path.
 * Returns true when the chunk was released and the transcript continues to delivery.
 */
@Suppress("ReturnCount")
internal fun ConversationDictationController.dropSilentFinalTail(
    sessionId: Long,
    target: ConversationDictationTarget,
    session: ConversationDictationRecognitionSession?,
    failure: ConversationDictationFailure,
): Boolean {
    if (!finishRequested || accumulatedTranscript.isBlank() || session == null) return false
    if (!runCatching(session::callerAudioIsFinalChunk).getOrDefault(false)) return false
    if (runCatching(session::callerAudioContainsSpeech).getOrNull() != false) return false
    if (!runCatching(session::acknowledgeCallerAudio).getOrDefault(false)) return false
    conversationDictationDiagnostic("event=caller_audio_tail_dropped failure=${failure.name} reason=silence")
    unresolvedRecognitionFailure = null
    clearRecognitionGeneration(cancel = false)
    advancePastConfirmedSilentCallerAudio(sessionId, target)
    return true
}

/** Keeps the logical session fenced while every captured chunk is recognized exactly once. */
internal fun ConversationDictationController.continueOrFinalizeCallerAudioDrain(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    // Reaching this helper means the previous chunk was resolved or there was no owned chunk.
    // Keep the recovery budget local to the next pending chunk instead of leaking it across the drain.
    recognition.resetFailure()
    if (runCatching(platform::callerAudioHasPending).getOrDefault(false)) {
        startRecognition(sessionId, target)
    } else {
        finalizeAccumulatedTranscript(sessionId, target)
    }
}
