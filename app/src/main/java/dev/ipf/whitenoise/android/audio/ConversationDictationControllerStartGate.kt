package dev.ipf.whitenoise.android.audio

/** One logical capture owns one absolute budget across fresh recognition and service identities. */
internal fun ConversationDictationController.captureDeadlineReached(): Boolean {
    val deadline = captureDeadlineElapsedMillis ?: return false
    return elapsedRealtime() >= deadline
}

/** An external Activity or useful transcript keeps its original gesture owner. */
internal fun ConversationDictationController.rejectsExistingSession(
    accountRef: String,
    groupIdHex: String,
    mode: ConversationDictationMode,
): Boolean {
    if (state is ConversationDictationState.ProviderActivityActive) {
        conversationDictationDiagnostic("event=request_start accepted=false reason=provider_activity_active")
        return true
    }
    val retainsTranscript =
        accumulatedTranscript.isNotBlank() ||
            completion.transcript.isNotBlank() ||
            (state as? ConversationDictationState.Failed)?.retainedTranscript?.isNotBlank() == true
    val rejected = blocksNewRequest && (hasSameTarget(accountRef, groupIdHex, mode) || retainsTranscript)
    if (rejected) {
        val reason = if (retainsTranscript) "transcript_pending" else "session_active"
        conversationDictationDiagnostic("event=request_start accepted=false reason=$reason")
    }
    return rejected
}

/** Capture closing and an unavailable target both reject a gesture before changing its owner. */
internal fun ConversationDictationController.canBeginCapture(
    accountRef: String,
    groupIdHex: String,
): Boolean {
    val reason =
        when {
            ConversationDictationKey.from(accountRef, groupIdHex) in parkedRecoveries -> "retained_audio"
            foregroundLease.releaseDeferred ||
                (state is ConversationDictationState.Failed && foregroundMicrophoneRequired) -> "capture_closing"
            state is ConversationDictationState.Failed &&
                runCatching(platform::callerAudioHasPending).getOrDefault(true) -> "retained_audio"
            hasUnrecoveredTranscript -> "unsaved_transcript"
            (state as? ConversationDictationState.Failed)?.reason == ConversationDictationFailure.DeliveryUnknown ->
                "delivery_unknown"
            !targetAvailable(accountRef, groupIdHex) -> "target_unavailable"
            else -> null
        }
    if (reason == null) return true
    conversationDictationDiagnostic("event=request_start accepted=false reason=$reason")
    return false
}

/** A new gesture cannot inherit capture closure or notification actions from its predecessor. */
internal fun ConversationDictationController.beginCaptureOwnership(): Boolean {
    // An explicit replacement can begin teardown after the initial gesture preflight passed.
    if (foregroundLease.releaseDeferred) {
        cancelSession()
        return false
    }
    captureClosureGeneration += 1L
    platform.beginCaptureSession()
    foregroundMicrophoneRequired = true
    notificationActionGeneration += 1L
    return true
}

/** Whether a duplicate request points at the current conversation and recognition mode. */
internal fun ConversationDictationController.hasSameTarget(
    accountRef: String,
    groupIdHex: String,
    mode: ConversationDictationMode,
): Boolean =
    state.target?.let { target ->
        target.matchesConversation(accountRef, groupIdHex) &&
            target.mode == mode
    } == true

/** A completed transcript treats a failed local check as indeterminate, never as permission to erase text. */
internal fun ConversationDictationController.completedTargetValidation(
    target: ConversationDictationTarget,
    includeReply: Boolean = true,
): ConversationDictationTargetValidation =
    try {
        val available =
            if (includeReply) targetAvailable(target) else targetAvailable(target.accountRef, target.groupIdHex)
        if (available) {
            ConversationDictationTargetValidation.Available
        } else {
            ConversationDictationTargetValidation.DefinitelyRemoved
        }
    } catch (_: RuntimeException) {
        ConversationDictationTargetValidation.Indeterminate
    }
