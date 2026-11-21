package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.MAX_SESSION_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationFollowUpOwner.Phase

/** True while a follow-up exists beside a previous dictation that is still being transcribed. */
internal val ConversationDictationController.followUpAwaitingPrevious: Boolean
    get() = followUp.pending != null && inActiveRecognitionState

/**
 * Whether another recording may start while this one is still being transcribed.
 *
 * Only app-owned capture qualifies: a provider that records for itself gives White Noise no audio
 * to hold back until the speech service is free again.
 */
internal val ConversationDictationController.canContinueDictation: Boolean
    get() =
        followUp.pending == null &&
            !followUp.discardClosing &&
            !foregroundMicrophoneRequired &&
            !microphone.held &&
            inActiveRecognitionState &&
            finishRequested &&
            !recoveryHandedToComposer &&
            retainedAudioFailure == null &&
            state.target?.mode == ConversationDictationMode.InApp &&
            runCatching(platform::followUpCaptureSupported).getOrDefault(false)

/** Accepts the follow-up gesture; recording begins once the previous recorder has closed. */
internal fun ConversationDictationController.continueDictation(): Boolean {
    val origin = state.target
    if (origin == null || !canContinueDictation) {
        conversationDictationDiagnostic("event=follow_up action=requested accepted=false")
        return false
    }
    val sessionId = ++nextSessionId
    followUp.pending = ConversationDictationFollowUpOwner.Pending(sessionId, origin, Phase.Arming)
    notificationActionGeneration += 1L
    conversationDictationDiagnostic("event=follow_up action=requested accepted=true follow_up_session=$sessionId")
    armFollowUpCapture()
    return true
}

/** Opens the follow-up recorder only after the previous one released the microphone. */
internal fun ConversationDictationController.armFollowUpCapture() {
    val pending = followUp.pending?.takeIf { it.phase == Phase.Arming } ?: return
    if (!inActiveRecognitionState || foregroundMicrophoneRequired || microphone.held) return
    val started =
        microphone.acquire(pending.sessionId) &&
            runCatching { onBeforeRecognition(pending.origin) }.isSuccess &&
            runCatching(platform::beginFollowUpCapture).getOrDefault(false)
    conversationDictationDiagnostic("event=follow_up action=capture started=$started")
    if (started) {
        followUp.pending = pending.copy(phase = Phase.Capturing)
    } else {
        followUp.pending = null
        finishPlaybackInterruption(pending.sessionId)
    }
}

/** Ends follow-up recording and keeps what was said; a recording that never opened is dropped. */
internal fun ConversationDictationController.sealFollowUp(mode: ConversationDictationDeliveryMode? = null) {
    val pending = followUp.pending ?: return
    when (pending.phase) {
        Phase.Sealed -> Unit
        Phase.Arming -> followUp.pending = null
        Phase.Capturing -> {
            followUp.pending = pending.copy(phase = Phase.Sealed, mode = mode)
            conversationDictationDiagnostic("event=follow_up action=sealed chosen=${mode?.name ?: "none"}")
            val closed: () -> Unit = { finishPlaybackInterruption(pending.sessionId) }
            if (!runCatching { platform.finishFollowUpCapture(closed) }.getOrDefault(false)) closed()
        }
    }
}

/** An explicit Paste or Send during the overlap belongs to the follow-up, never to the previous session. */
internal fun ConversationDictationController.followUpTakesCompletion(
    mode: ConversationDictationDeliveryMode?,
    automatic: Boolean,
): Boolean {
    if (mode == null || automatic || !followUpAwaitingPrevious) return false
    if (followUp.acceptsCompletion == true) sealFollowUp(mode)
    return true
}

/** Cancel during the overlap discards only the follow-up. */
internal fun ConversationDictationController.cancelFollowUp(): Boolean {
    if (followUp.pending == null) return false
    discardFollowUp()
    return true
}

internal fun ConversationDictationController.discardFollowUp() {
    val pending = followUp.pending ?: return
    followUp.pending = null
    followUp.discardClosing = true
    val closed: () -> Unit = {
        finishPlaybackInterruption(pending.sessionId)
        followUp.discardClosing = false
    }
    if (!runCatching { platform.discardFollowUpCapture(closed) }.getOrDefault(false)) closed()
    notificationActionGeneration += 1L
    conversationDictationDiagnostic("event=follow_up action=discarded")
}

/**
 * Moves the follow-up into the free session slot once the previous session has left it.
 *
 * The draft is read now, after the previous text was written or sent, so the follow-up lands at
 * the cursor as it stands and in order without any transcript stitching.
 */
@Suppress("ReturnCount")
internal fun ConversationDictationController.promoteFollowUp(
    previousDelivered: Boolean,
    previousSent: Boolean = false,
) {
    val pending = followUp.pending ?: return
    if (state !is ConversationDictationState.Idle) return
    val origin = pending.origin
    val draft =
        if (draftTargetRemoved) null else runCatching { readDraft(origin.accountRef, origin.groupIdHex) }.getOrNull()
    // A delivered Send consumed its reply; every other outcome leaves the composer's reply in place.
    val reply = origin.replyToMessageIdHex.takeUnless { previousDelivered && previousSent }
    if (pending.phase == Phase.Arming) {
        followUp.pending = null
        if (draft != null) requestStart(origin.accountRef, origin.groupIdHex, draft.value, reply)
        return
    }
    val target =
        draft?.let {
            origin.copy(
                capturedDraft = it.value.copy(composition = null),
                capturedDraftRevision = it.revision,
                replyToMessageIdHex = reply,
                finishAfterSilenceMillis = finishAfterSilenceMillis()?.takeIf { millis -> millis > 0L },
                silenceDeliveryMode = silenceDeliveryMode(),
            )
        }
    val adopted =
        target != null &&
            runCatching { targetAvailable(target) }.getOrDefault(false) &&
            runCatching(platform::adoptFollowUpCapture).getOrDefault(false)
    if (target == null || !adopted) {
        discardFollowUp()
        return
    }
    followUp.pending = null
    startPromotedFollowUp(pending, target, previousDelivered)
}

private fun ConversationDictationController.startPromotedFollowUp(
    pending: ConversationDictationFollowUpOwner.Pending,
    target: ConversationDictationTarget,
    previousDelivered: Boolean,
) {
    val sessionId = pending.sessionId
    clearMicrophoneRecovery()
    captureClosureGeneration += 1L
    notificationActionGeneration += 1L
    DictationDiagnostics.activeSession = sessionId
    DictationDiagnostics.record("event=session_started session=$sessionId mode=FollowUp phase=${pending.phase.name}")
    state = ConversationDictationState.Starting(sessionId, target)
    if (pending.phase == Phase.Capturing) {
        // Still recording: from here this is an ordinary live session over the already open capture.
        foregroundMicrophoneRequired = true
        captureDeadlineElapsedMillis = elapsedRealtime() + MAX_SESSION_MILLIS
        armSessionTimeout(sessionId, MAX_SESSION_MILLIS, "capture") {
            if (inActiveRecognitionState) {
                stop()
            } else {
                failOrRetainTranscript(sessionId, target, ConversationDictationFailure.TimedOut)
            }
        }
    } else {
        // Sealed audio drains like a retained recording and never reopens the microphone.
        foregroundMicrophoneRequired = false
        val mode = pending.mode
        if (mode == ConversationDictationDeliveryMode.SendOnFinish && !previousDelivered) {
            followUp.blockedSendSessionId = sessionId
        }
        completion.intent.choose(mode ?: ConversationDictationDeliveryMode.PasteIntoDraft, automatic = mode == null)
        finishRequested = true
        armCallerAudioDrainTimeout(sessionId, target)
        recognition.resetFailure()
        finishTiming.tap(elapsedRealtime())
    }
    startRecognition(sessionId, target)
}

/** A Send queued behind a dictation that did not deliver keeps its text for review instead of sending. */
internal fun ConversationDictationController.followUpSendBlocked(sessionId: Long): Boolean {
    if (followUp.blockedSendSessionId != sessionId) return false
    followUp.blockedSendSessionId = null
    conversationDictationDiagnostic("event=send_outcome outcome=retained reason=follow_up_previous_undelivered")
    return true
}
