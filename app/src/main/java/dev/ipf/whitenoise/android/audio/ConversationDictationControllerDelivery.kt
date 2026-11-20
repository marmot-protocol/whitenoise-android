package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.PROCESSING_TIMEOUT_MILLIS

/** Applies the captured paste-or-send policy after authoritative origin validation. */
internal fun ConversationDictationController.deliverTranscript(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String,
) {
    // A pressed Paste or Send sets this, and so does the silence timer from the choice stored
    // for automatic completion. Everything else — restoration, a target captured before that
    // setting existed — pastes into the draft rather than sending on its own.
    val deliveryMode = requestedDeliveryMode ?: ConversationDictationDeliveryMode.PasteIntoDraft
    finishTiming.delivered(elapsedRealtime(), deliveryMode)?.let { conversationDictationDiagnostic(it) }
    completion.intent.commit()
    if (deliveryMode == ConversationDictationDeliveryMode.SendOnFinish) {
        sendTranscriptOnFinish(sessionId, target, transcript)
        return
    }
    if (completion.draftRecovery.owns(sessionId, target)) {
        completeRecoveredPaste(sessionId, target, transcript)
        return
    }
    when (
        completion.paste(
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
        ConversationDictationCompletionOwner.PasteResult.Accepted -> {
            conversationDictationDiagnostic("event=paste_write outcome=accepted")
            complete(target)
        }
        ConversationDictationCompletionOwner.PasteResult.Retained ->
            fail(sessionId, target, ConversationDictationFailure.Unknown, retainedTranscript = transcript)
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

/** An already recovered insertion is replaced once and completes only after its fenced write succeeds. */
internal fun ConversationDictationController.completeRecoveredPaste(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String,
    failure: ConversationDictationFailure = ConversationDictationFailure.Unknown,
) {
    when (recoverRecognizedDraftResult(sessionId, target, transcript)) {
        ConversationDictationDraftRecovery.Result.Written,
        ConversationDictationDraftRecovery.Result.AlreadyPresent,
        ConversationDictationDraftRecovery.Result.Superseded,
        -> if (state.sessionId == sessionId) complete(target)
        ConversationDictationDraftRecovery.Result.Unavailable ->
            fail(sessionId, target, failure, retainedTranscript = transcript)
    }
}

/**
 * Sends the composer as it stands at finish: edits, pastes and staged attachments made while the user
 * was speaking go out with the transcript, which lands exactly where Paste would have put it. The
 * dispatch stays conditional on that snapshot, so a later edit still retains everything in the draft.
 */
internal fun ConversationDictationController.sendTranscriptOnFinish(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String,
) {
    // A saved recovery already holds the transcript in the draft, so the composer is the whole payload.
    val recovered = completion.draftRecovery.owns(sessionId, target)
    val recoveryHeld =
        !recovered ||
            recoverRecognizedDraftResult(sessionId, target, transcript) !=
            ConversationDictationDraftRecovery.Result.Unavailable
    val admission = completion.draftRecovery.sendTarget(sessionId, target, honorEdits = true)
    val current = runCatching { readDraft(target.accountRef, target.groupIdHex) }.getOrNull()
    val sendTarget =
        when {
            current == null || admission == null || !recoveryHeld -> null
            recovered -> admission.takeIf { it.capturedDraftRevision == current.revision }
            else -> target.rebasedOnto(current)
        }
    if (sendTarget == null) {
        val reason = if (current == null) "draft_unreadable" else "recovery_superseded"
        conversationDictationDiagnostic("event=send_outcome outcome=retained reason=$reason")
        fail(sessionId, target, ConversationDictationFailure.SendBlocked, retainedTranscript = transcript)
        return
    }
    if (!recovered && sendTarget != target) {
        conversationDictationDiagnostic("event=send_admission source=edited_draft")
    }
    val sendRequest = conversationDictationSendRequest(sendTarget, if (recovered) "" else transcript)
    val scope = targetValidationScope
    if (sendRequest == null || scope == null) {
        conversationDictationDiagnostic("event=send_outcome outcome=retained reason=dispatch_unavailable")
        fail(sessionId, target, ConversationDictationFailure.SendBlocked, retainedTranscript = transcript)
        return
    }
    clearRecognitionSession(cancel = false, releaseDurableSession = false)
    state = ConversationDictationState.Processing(sessionId, target)
    launchGuardedDispatch(sessionId, target, transcript, sendRequest, sendTarget)
}

/** The completion owner claims and dispatches one immutable send for this origin. */
internal fun ConversationDictationController.launchGuardedDispatch(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String,
    sendRequest: ConversationDictationSendRequest,
    admissionTarget: ConversationDictationTarget,
) {
    completion.dispatch.launchDispatch(
        scope = requireNotNull(targetValidationScope),
        context =
            ConversationDictationCompletionDispatchOwner.DispatchContext(
                sessionId,
                target,
                sendRequest,
                admissionTarget,
            ),
        transport =
            ConversationDictationCompletionDispatchOwner.Transport(
                PROCESSING_TIMEOUT_MILLIS,
                sendTranscriptIfOriginUnchanged,
            ),
        callbacks =
            ConversationDictationCompletionDispatchOwner.DispatchCallbacks(
                guards =
                    ConversationDictationCompletionDispatchOwner.DispatchGuards(
                        currentSessionId = { state.sessionId },
                        processing = { state is ConversationDictationState.Processing },
                        emptyDraft = this::emptyDraftForDispatch,
                        restoreDraft = this::restoreDraftAfterFailedDispatch,
                    ),
                outcomes =
                    ConversationDictationCompletionDispatchOwner.DispatchOutcomes(
                        onPending = {
                            conversationDictationDiagnostic("event=send_outcome outcome=pending_visible")
                            val key = ConversationDictationKey.from(target.accountRef, target.groupIdHex)
                            pendingSendRevisions[key] = (pendingSendRevisions[key] ?: 0) + 1
                            complete(target)
                        },
                        onAccepted = { claim ->
                            conversationDictationDiagnostic("event=send_outcome outcome=accepted")
                            if (claim.emptiedRevision == null) emptyDraftForDispatch(admissionTarget)
                            complete(target)
                        },
                        onRetained = { claim, accepted ->
                            val reason = if (accepted == null) "timeout_or_interrupted" else "not_confirmed"
                            conversationDictationDiagnostic("event=send_outcome outcome=retained reason=$reason")
                            retainFailedDispatchDraft(sessionId, target, admissionTarget, transcript, claim)
                        },
                    ),
            ),
    )
}

/** Our own clear/restore updates its fence; another writer never becomes the retry payload. */
internal fun ConversationDictationController.retainFailedDispatchDraft(
    sessionId: Long,
    target: ConversationDictationTarget,
    admissionTarget: ConversationDictationTarget,
    transcript: String,
    claim: ConversationDictationDispatchClaim,
) {
    claim.restoredRevision =
        restoreDraftAfterFailedDispatch(admissionTarget, claim.emptiedRevision) ?: claim.restoredRevision
    val hadRecovery = completion.draftRecovery.owns(sessionId, target)
    completion.draftRecovery.updateDispatch(sessionId, target, restoredRevision = claim.restoredRevision)
    // Without a saved receipt the admitted snapshot is the origin, so recovery keeps edits made while speaking.
    val failedTarget = if (hadRecovery) target else claim.recoveredTarget(admissionTarget)
    if (!draftTargetRemoved &&
        completedTargetValidation(target, includeReply = false) !=
        ConversationDictationTargetValidation.DefinitelyRemoved
    ) {
        recoverRecognizedDraft(
            sessionId,
            failedTarget,
            transcript,
            options =
                ConversationDictationDraftRecovery.Options(
                    restoreCapturedPrefix = claim.emptiedRevision != null && claim.restoredRevision == null,
                    ownedEmptyRevision = claim.emptiedRevision,
                ),
        )
    }
    retainUndeliveredTranscript(sessionId, failedTarget, transcript)
}

/**
 * Empties the composer in the frame the pending row appears, so the outgoing bubble and the
 * composer never show the same captured text at once. The write is conditional on the captured
 * revision, and its own new revision is what [restoreDraftAfterFailedDispatch] needs to put the
 * text back, so a newer user edit during the send keeps both the edit and its geometry.
 */
internal fun ConversationDictationController.emptyDraftForDispatch(target: ConversationDictationTarget): Long? =
    runCatching {
        writeDraft(target.accountRef, target.groupIdHex, target.capturedDraftRevision, TextFieldValue(""))
    }.getOrNull()

/** Puts the captured text back only while the composer still holds this dispatch's empty draft. */
internal fun ConversationDictationController.restoreDraftAfterFailedDispatch(
    target: ConversationDictationTarget,
    emptiedRevision: Long?,
): Long? {
    val revision = emptiedRevision ?: return null
    return runCatching {
        writeDraft(target.accountRef, target.groupIdHex, revision, target.capturedDraft)
    }.getOrNull()
}

internal fun ConversationDictationController.retainUndeliveredTranscript(
    sessionId: Long,
    target: ConversationDictationTarget,
    transcript: String,
) {
    if (completion.dispatch.dispatchedSessionId == sessionId) {
        conversationDictationDiagnostic("event=send_outcome outcome=retained reason=delivery_unknown")
        val recovered = recoverRecognizedDraft(sessionId, target, transcript)
        if (state.sessionId != sessionId) return
        val retainsAudio =
            retainedAudioFailure != null && runCatching(platform::callerAudioHasPending).getOrDefault(true)
        clearRecognitionSession(cancel = false, releaseDurableSession = !retainsAudio)
        if (!retainsAudio) resetTranscriptSession()
        recoveryHandedToComposer = false
        state =
            ConversationDictationState.Failed(
                sessionId = sessionId,
                target = target,
                reason = ConversationDictationFailure.DeliveryUnknown,
                retainedTranscript = transcript,
                draftRecovered = recovered,
            )
        notificationActionGeneration += 1L
        if (retainsAudio) protectRetainedRecovery(state as ConversationDictationState.Failed)
    } else {
        conversationDictationDiagnostic("event=send_outcome outcome=retained reason=not_dispatched")
        fail(sessionId, target, ConversationDictationFailure.SendBlocked, retainedTranscript = transcript)
    }
}
