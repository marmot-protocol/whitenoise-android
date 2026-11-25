package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.audio.ConversationDictationFollowUpOwner.Companion.MAX_QUEUED
import dev.ipf.whitenoise.android.audio.ConversationDictationFollowUpOwner.Phase

/** True while a follow-up exists beside a previous dictation that is still being transcribed. */
internal val ConversationDictationController.followUpAwaitingPrevious: Boolean
    get() = followUp.pending != null && inActiveRecognitionState

/** The session a Dictate gesture started; a waiting recording keeps its id when it is promoted. */
internal val ConversationDictationController.gestureSessionId: Long?
    get() = followUp.pending?.takeIf { it.phase != Phase.Sealed }?.sessionId ?: state.sessionId

/** Whether this conversation's waiting recording is open, so a held Dictate gesture can still finish it. */
internal fun ConversationDictationController.followUpRecordingFor(
    accountRef: String,
    groupIdHex: String,
): Boolean =
    followUp.pending?.let { pending ->
        pending.phase != Phase.Sealed && pending.target.matchesConversation(accountRef, groupIdHex)
    } == true

/** The active dictation's conversation; Continue records into it. */
internal val ConversationDictationController.continueTarget: ConversationDictationTarget?
    get() = state.target

/**
 * Whether another recording may start while this one is still being transcribed.
 *
 * Only app-owned capture qualifies: a provider that records for itself gives White Noise no audio
 * to hold back until the speech service is free again.
 */
internal val ConversationDictationController.canContinueDictation: Boolean
    get() = continueTarget?.let { canQueueDictation(it.accountRef, it.groupIdHex) } == true

/** Whether the current session has app-owned audio that can support a follow-up. */
internal val ConversationDictationController.supportsFollowUpCapture: Boolean
    get() = runCatching(platform::followUpCaptureSupported).getOrDefault(false)

/** Whether a recording for this conversation may wait behind the dictations already in progress. */
internal fun ConversationDictationController.canQueueDictation(
    accountRef: String,
    groupIdHex: String,
): Boolean =
    followUp.queue.size < MAX_QUEUED &&
        followUp.queue.all { it.phase == Phase.Sealed } &&
        !followUp.discardClosing &&
        !foregroundMicrophoneRequired &&
        !microphone.held &&
        inActiveRecognitionState &&
        finishRequested &&
        !recoveryHandedToComposer &&
        retainedAudioFailure == null &&
        state.target?.mode == ConversationDictationMode.InApp &&
        !hasParkedComposerAudio(accountRef, groupIdHex) &&
        supportsFollowUpCapture &&
        runCatching { targetAvailable(accountRef, groupIdHex) }.getOrDefault(false)

/** Accepts the Continue gesture for the active dictation's conversation. */
internal fun ConversationDictationController.continueDictation(): Boolean {
    val target = continueTarget ?: return rejectFollowUp()
    val key = ConversationDictationKey.from(target.accountRef, target.groupIdHex)
    val replyOwner = followUp.queue.lastOrNull { it.key == key }?.target ?: target
    return queueDictation(
        target.accountRef,
        target.groupIdHex,
        replyOwner.replyToMessageIdHex,
        replyOwner.outgoingTranslation,
    )
}

/**
 * Accepts a recording for any conversation while earlier dictations are still being transcribed.
 *
 * A composer supplies its current reply. Continue supplies the reply from its conversation's dictation.
 */
internal fun ConversationDictationController.queueDictation(
    accountRef: String,
    groupIdHex: String,
    replyToMessageIdHex: String?,
    outgoingTranslation: ConversationDictationTranslation? = null,
): Boolean {
    val previous = followUp.pending?.target ?: state.target
    if (previous == null || !canQueueDictation(accountRef, groupIdHex)) return rejectFollowUp()
    val target =
        previous.copy(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            replyToMessageIdHex = replyToMessageIdHex,
            outgoingTranslation = outgoingTranslation,
        )
    if (followUp.queue.isEmpty()) {
        followUp.resetChain()
        followUp.drainingKey = state.target?.let { ConversationDictationKey.from(it.accountRef, it.groupIdHex) }
    }
    val sessionId = ++nextSessionId
    followUp.queue = followUp.queue + ConversationDictationFollowUpOwner.Pending(sessionId, target, Phase.Arming)
    notificationActionGeneration += 1L
    val sameConversation = previous.matchesConversation(accountRef, groupIdHex)
    conversationDictationDiagnostic(
        "event=follow_up action=requested accepted=true follow_up_session=$sessionId " +
            "queued=${followUp.queue.size} same_conversation=$sameConversation",
    )
    armFollowUpCapture()
    return true
}

private fun rejectFollowUp(): Boolean {
    conversationDictationDiagnostic("event=follow_up action=requested accepted=false")
    return false
}

/** Opens the follow-up recorder only after the previous one released the microphone. */
internal fun ConversationDictationController.armFollowUpCapture() {
    val pending = followUp.pending?.takeIf { it.phase == Phase.Arming } ?: return
    if (!inActiveRecognitionState || foregroundMicrophoneRequired || microphone.held) return
    val started =
        microphone.acquire(pending.sessionId) &&
            runCatching { onBeforeRecognition(pending.target) }.isSuccess &&
            runCatching { platform.followUpCaptures()?.beginFollowUp(pending.sessionId) }.getOrNull() == true
    conversationDictationDiagnostic("event=follow_up action=capture started=$started")
    if (started) {
        followUp.replaceNewest(pending.copy(phase = Phase.Capturing))
    } else {
        followUp.removeNewest()
        finishPlaybackInterruption(pending.sessionId)
    }
}

/** Ends follow-up recording and keeps what was said; a recording that never opened is dropped. */
internal fun ConversationDictationController.sealFollowUp(mode: ConversationDictationDeliveryMode? = null) {
    val pending = followUp.pending ?: return
    when (pending.phase) {
        Phase.Sealed -> Unit
        Phase.Arming -> followUp.removeNewest()
        Phase.Capturing -> {
            followUp.replaceNewest(pending.copy(phase = Phase.Sealed, mode = mode))
            conversationDictationDiagnostic("event=follow_up action=sealed chosen=${mode?.name ?: "none"}")
            val closed: () -> Unit = { finishPlaybackInterruption(pending.sessionId) }
            val finished = runCatching { platform.followUpCaptures()?.finishFollowUp(pending.sessionId, closed) }
            if (finished.getOrNull() != true) {
                closed()
            }
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
