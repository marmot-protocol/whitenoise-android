package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.CALLER_AUDIO_FAILURE_CLOSE_TIMEOUT_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.RETAINED_RECOVERY_TIMEOUT_MILLIS

/** Recorder closure has its own deadline so a recognition retry cannot disarm it. */
internal fun ConversationDictationController.closeRecoveryCapture(
    failed: ConversationDictationState.Failed,
    drainAlreadyExpired: Boolean,
    captureAlreadyDiscarded: Boolean = false,
) {
    val sessionId = failed.sessionId
    val captureGeneration = captureClosureGeneration
    val token = notificationSessionToken
    val onClosed: () -> Unit = {
        finishPlaybackInterruption(sessionId)
        acknowledgeCaptureClosed(sessionId, captureGeneration)
        val currentFailure =
            (state as? ConversationDictationState.Failed)?.takeIf {
                it.sessionId == sessionId &&
                    captureClosureGeneration == captureGeneration &&
                    notificationSessionToken == token
            }
        if (currentFailure != null) {
            val retainedAudio = runCatching(platform::callerAudioHasPending).getOrDefault(true)
            handRecoveryToComposer(currentFailure)
            parkClosedRecovery(currentFailure)
            if (!retainedAudio && currentFailure.retainedTranscript.isNullOrBlank()) releaseDurableSessionLease()
        }
    }
    captureClosureTimeoutHandle?.cancel()
    captureClosureTimeoutHandle =
        diagnosticTimeout(
            sessionId,
            CALLER_AUDIO_FAILURE_CLOSE_TIMEOUT_MILLIS,
            "caller_audio_failure_close",
            isCurrent = {
                state.sessionId == sessionId &&
                    captureClosureGeneration == captureGeneration &&
                    foregroundMicrophoneRequired
            },
            callback = {
                if (state === failed) {
                    completion.intent.reset()
                    completion.intent.choose(ConversationDictationDeliveryMode.PasteIntoDraft)
                    handRecoveryToComposer(failed)
                }
                requestForcedRecoveryCaptureClosure(onClosed, acknowledgeAbsentCapture = !captureAlreadyDiscarded)
            },
        )
    // Discard/destroy already registered the real closure callback. A provider-owned
    // capture cannot be declared closed just because there is no caller-audio recorder.
    if (captureAlreadyDiscarded) return
    val platformOwnsClosure = runCatching { platform.finishCallerAudioCapture(onClosed) }.getOrNull()
    if (platformOwnsClosure == false) {
        onClosed()
    } else if (drainAlreadyExpired) {
        // The drain deadline cannot stand in for an actual recorder-closure acknowledgement.
        requestForcedRecoveryCaptureClosure(onClosed)
    }
}

/** Closed recovery belongs to its origin, not the app-wide active recording slot. */
internal fun ConversationDictationController.parkClosedRecovery(failed: ConversationDictationState.Failed) {
    if (state !== failed || runCatching(platform::callerAudioHasPending).getOrNull() != true) return
    val access =
        ConversationDictationComposerAccess(
            failed.sessionId,
            notificationActionGeneration,
            ConversationDictationComposerPhase.RemainingAudio,
            failed.cause ?: failed.reason,
        )
    val parked = keepComposerAudioForLater(access)
    conversationDictationDiagnostic(
        "event=recovery_owner_release session=${failed.sessionId} outcome=${if (parked) "parked" else "protected"}",
    )
}

/** Absence of a capture permits closure; an exception proves no such absence. */
internal fun ConversationDictationController.requestForcedRecoveryCaptureClosure(
    onClosed: () -> Unit,
    acknowledgeAbsentCapture: Boolean = true,
) {
    runCatching { platform.forceCallerAudioCaptureClosure(onClosed) }
        .onSuccess { ownsClosure -> if (!ownsClosure && acknowledgeAbsentCapture) onClosed() }
        .onFailure {
            conversationDictationDiagnostic("event=caller_audio_force_close_failed type=${it.javaClass.simpleName}")
        }
}

/** Only a verified save or respected editor change can release completion controls. */
internal fun ConversationDictationController.handRecoveryToComposer(failed: ConversationDictationState.Failed) {
    if (state !== failed ||
        failed.reason == ConversationDictationFailure.DeliveryUnknown
    ) {
        return
    }
    if (!failed.draftRecovered && !failed.draftSuperseded) {
        recoveryHandedToComposer = false
        return
    }
    completion.intent.reset()
    completion.intent.choose(ConversationDictationDeliveryMode.PasteIntoDraft)
    if (!foregroundMicrophoneRequired && !runCatching(platform::callerAudioHasPending).getOrDefault(true)) {
        retainedAudioFailure = null
        recoveryHandedToComposer = false
        clearRecognitionSession(cancel = false)
        completion.draftRecovery.reset()
        resetTranscriptSession()
        state = ConversationDictationState.Idle
    } else if (!recoveryHandedToComposer) {
        recoveryHandedToComposer = true
        notificationActionGeneration += 1L
    }
    if (!foregroundMicrophoneRequired) parkClosedRecovery(failed)
    promoteFollowUp(previousDelivered = false)
}

/** Bounds foreground protection without treating elapsed time as permission to destroy recovery data. */
internal fun ConversationDictationController.protectRetainedRecovery(failed: ConversationDictationState.Failed) {
    if (recoveryProtectionExpired || recoveryDeadlineElapsedMillis != null) return
    if (failed.retainedTranscript.isNullOrBlank() &&
        !runCatching(platform::callerAudioHasPending).getOrDefault(true)
    ) {
        return
    }
    recoveryDeadlineElapsedMillis = elapsedRealtime() + RETAINED_RECOVERY_TIMEOUT_MILLIS
    recoveryTimeoutHandle =
        scheduleTimeout(RETAINED_RECOVERY_TIMEOUT_MILLIS) {
            if ((state as? ConversationDictationState.Failed)?.sessionId == failed.sessionId) {
                expireRetainedRecoveryIfDue()
            }
        }
}

/** Sleep can expire the service lease, but only a user decision or acknowledgment consumes PCM. */
internal fun ConversationDictationController.expireRetainedRecoveryIfDue() {
    val deadline = recoveryDeadlineElapsedMillis ?: return
    if (state !is ConversationDictationState.Failed || elapsedRealtime() < deadline) return
    recoveryDeadlineElapsedMillis = null
    recoveryTimeoutHandle?.cancel()
    recoveryTimeoutHandle = null
    recoveryProtectionExpired = true
    pendingForegroundRecoverySessionId = null
    notificationActionGeneration += 1L
    if (!foregroundMicrophoneRequired) releaseDurableSessionLease()
    discardFollowUp()
    onRecoveryExpired()
}

/** Native closure releases an expired lease even when its timeout already ran during teardown. */
internal fun ConversationDictationController.releaseExpiredRecoveryProtection() {
    expireRetainedRecoveryIfDue()
    if (recoveryProtectionExpired && !foregroundMicrophoneRequired) releaseDurableSessionLease()
}
