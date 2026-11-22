package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationController.Companion.MAX_SESSION_MILLIS
import dev.ipf.whitenoise.android.audio.ConversationDictationFollowUpOwner.Phase

/** Cancel during the overlap discards only the newest recording. */
internal fun ConversationDictationController.cancelFollowUp(): Boolean {
    if (followUp.pending == null) return false
    discardFollowUp()
    return true
}

/** Discards the newest recording. */
internal fun ConversationDictationController.discardFollowUp() {
    val pending = followUp.pending ?: return
    followUp.removeNewest()
    discardFollowUpCapture(pending)
    notificationActionGeneration += 1L
    conversationDictationDiagnostic("event=follow_up action=discarded queued=${followUp.queue.size}")
}

/** Discards every waiting recording that matches; text was never recognized from them. */
internal fun ConversationDictationController.discardFollowUps(
    reason: String,
    matches: (ConversationDictationFollowUpOwner.Pending) -> Boolean = { true },
) {
    val dropped = followUp.queue.filter(matches)
    if (dropped.isEmpty()) return
    followUp.queue = followUp.queue - dropped.toSet()
    if (followUp.queue.isEmpty()) followUp.resetChain()
    dropped.forEach { discardFollowUpCapture(it) }
    notificationActionGeneration += 1L
    conversationDictationDiagnostic(
        "event=follow_up action=discarded reason=$reason count=${dropped.size} queued=${followUp.queue.size}",
    )
}

/** A removed conversation loses its retained audio and every recording still waiting for it. */
internal fun ConversationDictationController.forgetConversationAudio(
    accountRef: String,
    groupIdHex: String,
) {
    parkedRecoveries.remove(ConversationDictationKey.from(accountRef, groupIdHex))?.audio?.discard()
    discardFollowUps("target_removed") { it.target.matchesConversation(accountRef, groupIdHex) }
}

/** An account that became unavailable loses its retained audio and waiting recordings in every chat. */
internal fun ConversationDictationController.forgetAccountAudio(accountRef: String) {
    parkedRecoveries.keys.filter { it.accountRef.equals(accountRef, ignoreCase = true) }.forEach { key ->
        parkedRecoveries.remove(key)?.audio?.discard()
    }
    discardFollowUps("account_unavailable") { it.target.accountRef.equals(accountRef, ignoreCase = true) }
}

private fun ConversationDictationController.discardFollowUpCapture(
    pending: ConversationDictationFollowUpOwner.Pending,
) {
    followUp.discardsClosing += 1
    val closed: () -> Unit = {
        finishPlaybackInterruption(pending.sessionId)
        followUp.discardsClosing = (followUp.discardsClosing - 1).coerceAtLeast(0)
    }
    if (runCatching { platform.followUpCaptures()?.discardFollowUp(pending.sessionId, closed) }.getOrNull() != true) {
        closed()
    }
}

/**
 * Moves the oldest waiting recording into the free session slot once the previous session has left it.
 *
 * The draft is read now, after the previous text was written or sent, so the recording lands at the
 * cursor as it stands and in order without any transcript stitching.
 */
@Suppress("ReturnCount")
internal fun ConversationDictationController.promoteFollowUp(previousDelivered: Boolean) {
    if (state !is ConversationDictationState.Idle || followUp.head == null) return
    val finished = followUp.drainingKey
    if (draftTargetRemoved) {
        draftTargetRemoved = false
        discardFollowUps("target_removed") { it.key == finished }
    }
    val pending = followUp.head ?: return
    if (finished != null && !previousDelivered) followUp.undelivered += finished
    followUp.drainingKey = pending.key
    val queuedTarget = pending.target
    val draft = runCatching { readDraft(queuedTarget.accountRef, queuedTarget.groupIdHex) }.getOrNull()
    val sendBlocked = pending.key in followUp.undelivered
    if (pending.phase == Phase.Arming) {
        dequeueFollowUpHead()
        if (draft != null) {
            requestStart(queuedTarget.accountRef, queuedTarget.groupIdHex, draft.value, queuedTarget.replyToMessageIdHex)
        }
        return
    }
    val target =
        draft?.let {
            queuedTarget.copy(
                capturedDraft = it.value.copy(composition = null),
                capturedDraftRevision = it.revision,
                finishAfterSilenceMillis = finishAfterSilenceMillis()?.takeIf { millis -> millis > 0L },
                silenceDeliveryMode = silenceDeliveryMode(),
            )
        }
    val adopted =
        target != null &&
            runCatching { targetAvailable(target) }.getOrDefault(false) &&
            runCatching { platform.followUpCaptures()?.adoptFollowUp(pending.sessionId) }.getOrNull() == true
    dequeueFollowUpHead()
    if (target == null || !adopted) {
        discardFollowUpCapture(pending)
        conversationDictationDiagnostic("event=follow_up action=discarded reason=promotion")
        // Its recording is lost; the next one in line is still independent of it.
        promoteFollowUp(previousDelivered = false)
        return
    }
    startPromotedFollowUp(pending, target, sendBlocked)
}

internal fun ConversationDictationController.clearConsumedFollowUpReply(target: ConversationDictationTarget) {
    val reply = target.replyToMessageIdHex ?: return
    followUp.queue = followUp.queue.map { pending ->
        val queued = pending.target
        if (queued.matchesConversation(target.accountRef, target.groupIdHex) && queued.replyToMessageIdHex == reply) {
            pending.copy(target = queued.copy(replyToMessageIdHex = null))
        } else {
            pending
        }
    }
}

private fun ConversationDictationController.dequeueFollowUpHead() {
    followUp.queue = followUp.queue.drop(1)
    if (followUp.queue.isEmpty()) followUp.resetChain()
}

private fun ConversationDictationController.startPromotedFollowUp(
    pending: ConversationDictationFollowUpOwner.Pending,
    target: ConversationDictationTarget,
    sendBlocked: Boolean,
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
        if (mode == ConversationDictationDeliveryMode.SendOnFinish && sendBlocked) {
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
