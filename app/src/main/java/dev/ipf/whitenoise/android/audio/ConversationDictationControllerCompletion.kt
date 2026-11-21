package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.PROCESSING_TIMEOUT_MILLIS
import kotlinx.coroutines.launch

/** Keeps durable ownership while asynchronously validating the origin through MDK. */
@Suppress("ReturnCount")
internal fun ConversationDictationController.validateAndDeliverTranscript(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String,
) {
    completion.recordTranscript(transcript)
    if (usesLocalDraftOnlyDelivery(target)) {
        conversationDictationDiagnostic(
            "event=target_validation phase=delivery result=Available source=draft_only",
        )
        deliverTranscript(sessionId, target, transcript)
        return
    }
    val validator = targetValidator
    val validationScope = targetValidationScope
    if (validator == null || validationScope == null) {
        conversationDictationDiagnostic(
            "event=target_validation phase=delivery result=" +
                "${ConversationDictationTargetValidation.Available.name} source=local",
        )
        deliverTranscript(sessionId, target, transcript)
        return
    }
    if (completion.validation.validationInProgress(sessionId)) return
    // Recognition has produced its terminal result. Release the provider
    // and microphone immediately while the authoritative MDK membership
    // probe runs; session-id ownership still rejects replacement/stale work.
    clearRecognitionSession(cancel = false, releaseDurableSession = false)
    state = ConversationDictationState.Processing(sessionId, target)
    val request = completion.validation.beginValidation(sessionId) ?: return
    validationScope.launch {
        val validation = completion.validation.validate(PROCESSING_TIMEOUT_MILLIS, validator, target)
        if (!completion.validation.finishValidation(request)) return@launch
        finishValidatedTranscript(sessionId, target, transcript, validation)
    }
}

/** A finished membership probe must still match the session and immutable send origin. */
internal fun ConversationDictationController.finishValidatedTranscript(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String,
    validation: ConversationDictationTargetValidation,
) {
    conversationDictationDiagnostic("event=target_validation phase=delivery result=${validation.name}")
    if (state.sessionId != sessionId || state !is ConversationDictationState.Processing) return
    val localValidation = completedTargetValidation(target)
    if (validation == ConversationDictationTargetValidation.DefinitelyRemoved ||
        completedTargetValidation(target, includeReply = false) ==
        ConversationDictationTargetValidation.DefinitelyRemoved
    ) {
        draftTargetRemoved = true
    }
    if (
        validation != ConversationDictationTargetValidation.Available ||
        localValidation != ConversationDictationTargetValidation.Available
    ) {
        if (validation == ConversationDictationTargetValidation.Available) {
            conversationDictationDiagnostic(
                "event=target_validation phase=delivery result=${localValidation.name} source=local",
            )
        }
        fail(
            sessionId,
            target,
            ConversationDictationFailure.Unknown,
            retainedTranscript = transcript,
        )
        return
    }
    deliverTranscript(sessionId, target, transcript)
}

/** An explicit in-app Paste is local-only, so a transient MDK membership read must not block it. */
internal fun ConversationDictationController.usesLocalDraftOnlyDelivery(target: ConversationDictationTarget): Boolean =
    target.mode == ConversationDictationMode.InApp &&
        requestedDeliveryMode == ConversationDictationDeliveryMode.PasteIntoDraft

/** Releases durable ownership and publishes a completion revision for the origin composer. */
internal fun ConversationDictationController.complete(target: ConversationDictationTarget) {
    conversationDictationDiagnostic("event=session_finished outcome=completed")
    completion.dispatch.finishDispatch()
    val sessionId = state.sessionId
    val interruption = unresolvedRecognitionFailure?.takeIf { it == ConversationDictationFailure.Interrupted }
    val failure = retainedAudioFailure ?: interruption
    val transcript = completion.transcript.ifBlank { accumulatedTranscript }.trim()
    val retainsAudio = failure != null && runCatching(platform::callerAudioHasPending).getOrDefault(true)
    val retainsFailure = retainsAudio || interruption != null
    if (sessionId != null && failure != null && retainsFailure) {
        if (requestedDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish) {
            completion.draftRecovery.consumeSubmittedTranscript(sessionId, target, transcript)
        }
        // The draft write already succeeded. Publish its interruption notice without a second recovery write.
        clearRecognitionSession(cancel = false, releaseDurableSession = !retainsAudio)
        recoveryHandedToComposer = true
        completion.dispatch.clearDispatchOwnership()
        completion.intent.reset()
        completion.intent.choose(ConversationDictationDeliveryMode.PasteIntoDraft)
        val failed =
            ConversationDictationState.Failed(
                sessionId,
                target,
                failure,
                retainedTranscript = transcript,
                recognitionIncomplete = true,
                draftRecovered = true,
            )
        val key = ConversationDictationKey.from(target.accountRef, target.groupIdHex)
        completionRevisions[key] = (completionRevisions[key] ?: 0) + 1
        state = failed
        notificationActionGeneration += 1L
        protectRetainedRecovery(failed)
        return
    }
    retainedAudioFailure = null
    val sent = requestedDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish
    clearRecognitionSession(cancel = false)
    completion.draftRecovery.reset()
    resetTranscriptSession()
    val key = ConversationDictationKey.from(target.accountRef, target.groupIdHex)
    completionRevisions[key] = (completionRevisions[key] ?: 0) + 1
    state = ConversationDictationState.Idle
    promoteFollowUp(previousDelivered = true, previousSent = sent)
}

/** Saves text locally even when reply or network validation prevents sending. */
internal fun ConversationDictationController.recoverRecognizedDraft(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String?,
    options: ConversationDictationDraftRecovery.Options = ConversationDictationDraftRecovery.Options(),
): Boolean = recoverRecognizedDraftResult(sessionId, target, transcript, options).draftRecovered

@Suppress("ReturnCount")
internal fun ConversationDictationController.recoverRecognizedDraftResult(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String?,
    options: ConversationDictationDraftRecovery.Options = ConversationDictationDraftRecovery.Options(),
): ConversationDictationDraftRecovery.Result {
    if (transcript.isNullOrBlank() || draftTargetRemoved || state.sessionId != sessionId) {
        return ConversationDictationDraftRecovery.Result.Unavailable
    }
    val available =
        completedTargetValidation(target, includeReply = false) !=
            ConversationDictationTargetValidation.DefinitelyRemoved
    val result =
        if (available) {
            completion.draftRecovery.recoverWithResult(
                sessionId,
                target,
                transcript,
                options,
            )
        } else {
            ConversationDictationDraftRecovery.Result.Unavailable
        }
    if (state.sessionId != sessionId) return ConversationDictationDraftRecovery.Result.Unavailable
    val published = result == ConversationDictationDraftRecovery.Result.Written
    if (published) {
        val key = ConversationDictationKey.from(target.accountRef, target.groupIdHex)
        completionRevisions[key] = (completionRevisions[key] ?: 0) + 1
    }
    val recovered = result.draftRecovered
    conversationDictationDiagnostic(
        "event=paste_write outcome=${if (recovered) "accepted" else "retained"} " +
            "source=latest_draft published=$published",
    )
    return result
}
