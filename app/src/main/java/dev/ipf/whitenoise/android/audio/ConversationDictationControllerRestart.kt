package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.GENERATION_RESTART_DELAY_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.MAX_CONSECUTIVE_RAPID_EMPTY_GENERATIONS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.ORDINARY_SILENCE_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.PERMISSION_RETRY_DELAY_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS

/** Keeps manual dictation alive through silence while bounding broken automatic sessions. */
internal fun ConversationDictationController.restartAfterNoSpeech(
    sessionId: Long,
    target: ConversationDictationTarget,
    readyAtElapsedMillis: Long?,
) {
    val readyDurationMillis = readyAtElapsedMillis?.let { elapsedRealtime() - it }
    val manualNoSpeech =
        target.finishAfterSilenceMillis == null &&
            unresolvedRecognitionFailure?.isEmptyRecognition == true
    val ordinarySilence = readyDurationMillis != null && readyDurationMillis >= ORDINARY_SILENCE_MILLIS
    // Manual completion keeps the logical session alive through rapid provider empty finals.
    val rapid = !manualNoSpeech && !ordinarySilence
    if (restart.noteNoSpeech(rapid, MAX_CONSECUTIVE_RAPID_EMPTY_GENERATIONS)) {
        failOrRetainTranscript(
            sessionId,
            target,
            unresolvedRecognitionFailure ?: ConversationDictationFailure.NoSpeech,
        )
        return
    }
    scheduleRestart(sessionId, target, GENERATION_RESTART_DELAY_MILLIS, "no_speech")
}

/** Retries one code-9 denial only after fresh native-mode safety checks. */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // Keep retry fencing and failure mapping in one decision.
internal fun ConversationDictationController.recoverPermissionFailure(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    val targetStillAvailable =
        try {
            targetAvailable(target)
        } catch (_: RuntimeException) {
            if (state.sessionId == sessionId) {
                failOrRetainTranscript(sessionId, target, ConversationDictationFailure.Unknown)
            }
            return
        }
    if (state.sessionId != sessionId || finishRequested) return
    if (!targetStillAvailable) {
        abortSessionPreservingTranscript("target_unavailable")
        return
    }
    val retryFailure =
        try {
            when {
                !platform.recognitionAvailable() -> ConversationDictationFailure.ProviderUnavailable
                else ->
                    recognitionPermissionFailure().takeUnless {
                        it == ConversationDictationFailure.ProviderAccessRejected
                    }
            }
        } catch (_: RuntimeException) {
            ConversationDictationFailure.Unknown
        }
    if (state.sessionId != sessionId || finishRequested) return
    if (retryFailure != null) {
        failOrRetainTranscript(sessionId, target, retryFailure)
        return
    }
    unresolvedRecognitionFailure = ConversationDictationFailure.ProviderAccessRejected
    if (!restart.takePermissionRetry()) {
        // A service that keeps rejecting an otherwise granted caller cannot serve app-owned
        // capture. Report it here instead of opening the provider's own recognition UI; the
        // failure action offers voice-input settings, which is where a selectable service is
        // chosen, and the composer keeps its own controls throughout.
        failOrRetainTranscript(sessionId, target, ConversationDictationFailure.ProviderAccessRejected)
        return
    }
    scheduleRestart(sessionId, target, PERMISSION_RETRY_DELAY_MILLIS, "permission")
}

/** A provider's code 9 does not mean White Noise lacks its own microphone grant. */
internal fun ConversationDictationController.recognitionPermissionFailure(): ConversationDictationFailure =
    try {
        when (platform.microphoneAccess()) {
            ConversationDictationMicrophoneAccess.Granted -> ConversationDictationFailure.ProviderAccessRejected
            ConversationDictationMicrophoneAccess.RuntimePermissionRequired ->
                ConversationDictationFailure.PermissionDenied
            ConversationDictationMicrophoneAccess.AppOpDenied ->
                ConversationDictationFailure.PermissionPermanentlyDenied
            ConversationDictationMicrophoneAccess.MicrophoneMuted -> ConversationDictationFailure.MicrophoneMuted
        }
    } catch (_: RuntimeException) {
        ConversationDictationFailure.Unknown
    }

/** Retries provider disconnects with one logical-session-wide bounded backoff budget. */
internal fun ConversationDictationController.recoverProviderDisconnect(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    val delayMillis = restart.nextDisconnectDelay(PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS)
    if (delayMillis == null) {
        failOrRetainTranscript(sessionId, target, ConversationDictationFailure.ProviderDisconnected)
        return
    }
    scheduleRestart(sessionId, target, delayMillis, "provider_disconnected")
}

/** Opens a fresh provider generation after a fenced, cancellable delay. */
@Suppress("ComplexCondition") // All four predicates fence one immutable scheduled restart.
internal fun ConversationDictationController.scheduleRestart(
    sessionId: Long,
    target: ConversationDictationTarget,
    delayMillis: Long,
    reason: String,
) {
    if (state.sessionId != sessionId || finishRequested) return
    cancelPendingRestart()
    state = ConversationDictationState.Starting(sessionId, target)
    val scheduledRestartId = restart.reserve()
    conversationDictationDiagnostic(
        "event=recognizer_restart_scheduled reason=$reason delay_ms=$delayMillis restart=$scheduledRestartId",
    )
    armSilenceDeadline(sessionId, target)
    restart.install(
        scheduledRestartId,
        scheduleTimeout(delayMillis) {
            if (
                state.sessionId == sessionId &&
                state is ConversationDictationState.Starting &&
                !finishRequested &&
                restart.consume(scheduledRestartId)
            ) {
                resumeRecognitionAfterDelay(sessionId, target)
            }
        },
    )
}

/** Rechecks every revocable native precondition after the delay and before reacquiring capture. */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // Keep every revocable precondition in one fail-closed boundary.
internal fun ConversationDictationController.resumeRecognitionAfterDelay(
    sessionId: Long,
    target: ConversationDictationTarget,
) {
    val targetStillAvailable =
        try {
            targetAvailable(target)
        } catch (_: RuntimeException) {
            if (state.sessionId == sessionId) {
                failOrRetainTranscript(sessionId, target, ConversationDictationFailure.Unknown)
            }
            return
        }
    if (state.sessionId != sessionId || finishRequested) return
    if (!targetStillAvailable) {
        abortSessionPreservingTranscript("target_unavailable")
        return
    }
    val failure =
        try {
            when {
                !platform.recognitionAvailable() -> ConversationDictationFailure.ProviderUnavailable
                else ->
                    when (platform.microphoneAccess()) {
                        ConversationDictationMicrophoneAccess.Granted -> null
                        ConversationDictationMicrophoneAccess.RuntimePermissionRequired ->
                            ConversationDictationFailure.PermissionDenied
                        ConversationDictationMicrophoneAccess.AppOpDenied ->
                            ConversationDictationFailure.PermissionPermanentlyDenied
                        ConversationDictationMicrophoneAccess.MicrophoneMuted ->
                            ConversationDictationFailure.MicrophoneMuted
                    }
            }
        } catch (_: RuntimeException) {
            ConversationDictationFailure.Unknown
        }
    if (state.sessionId != sessionId || finishRequested) return
    if (failure != null) {
        failOrRetainTranscript(sessionId, target, failure)
        return
    }
    startRecognition(sessionId, target)
}

internal fun ConversationDictationController.cancelPendingRestart() {
    restart.cancel()
}
