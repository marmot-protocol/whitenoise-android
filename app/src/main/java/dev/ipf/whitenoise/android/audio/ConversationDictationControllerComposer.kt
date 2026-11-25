package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.ConversationDictationState.Failed

/** No queued restart: resolution only makes the next explicit Dictate gesture eligible. */
internal fun ConversationDictationController.requestComposerStart(
    accountRef: String,
    groupIdHex: String,
    draft: TextFieldValue,
    replyToMessageIdHex: String?,
    outgoingTranslation: ConversationDictationTranslation? = null,
): ConversationDictationComposerAccess {
    expireRetainedRecoveryIfDue()
    val access = composerAccess(accountRef, groupIdHex)
    if (access.phase != ConversationDictationComposerPhase.Ready) return access
    val started = requestStart(accountRef, groupIdHex, draft, replyToMessageIdHex, outgoingTranslation)
    val rejected = composerAccess(accountRef, groupIdHex)
    return when {
        started -> access
        rejected.phase == ConversationDictationComposerPhase.Ready ->
            rejected.copy(
                phase = ConversationDictationComposerPhase.Protected,
                failure = ConversationDictationFailure.Unknown,
            )
        else -> rejected
    }
}

internal fun ConversationDictationController.parkedComposerAccess(
    accountRef: String,
    groupIdHex: String,
): ConversationDictationComposerAccess? =
    parkedRecoveries[ConversationDictationKey.from(accountRef, groupIdHex)]?.let { parked ->
        ConversationDictationComposerAccess(
            parked.failure.sessionId,
            parked.actionGeneration,
            ConversationDictationComposerPhase.RemainingAudio,
            parked.failure.cause ?: parked.failure.reason,
        )
    }

internal fun ConversationDictationController.failedComposerPhase(failed: Failed): ConversationDictationComposerPhase =
    when {
        foregroundMicrophoneRequired -> ConversationDictationComposerPhase.ClosingMicrophone
        failed.reason == ConversationDictationFailure.DeliveryUnknown || hasUnrecoveredTranscript ->
            ConversationDictationComposerPhase.Protected
        !runCatching { targetAvailable(failed.target) }.getOrDefault(false) ->
            ConversationDictationComposerPhase.TargetUnavailable
        else ->
            when (runCatching(platform::callerAudioHasPending).getOrNull()) {
                null -> ConversationDictationComposerPhase.AudioStateUnavailable
                true -> ConversationDictationComposerPhase.RemainingAudio
                false -> ConversationDictationComposerPhase.Ready
            }
    }

@Suppress("ReturnCount") // A refused restore leaves the original recording in its slot.
internal fun ConversationDictationController.restoreParkedRecovery(
    access: ConversationDictationComposerAccess,
): Boolean {
    if (state !is ConversationDictationState.Idle || foregroundMicrophoneRequired) return false
    val entry =
        parkedRecoveries.entries.firstOrNull {
            it.value.failure.sessionId == access.sessionId && it.value.actionGeneration == access.actionGeneration
        } ?: return false
    val parked = entry.value
    val restored =
        runCatching { targetAvailable(parked.failure.target) && parked.audio.restore() }.getOrDefault(false)
    if (!restored) return false
    parkedRecoveries.remove(entry.key)
    draftTargetRemoved = false
    captureClosureGeneration += 1L
    notificationActionGeneration += 1L
    completion.restoreDraftRecovery(parked.draft)
    recognition.restore(parked.recognition)
    retainedAudioFailure = parked.failure.cause ?: parked.failure.reason
    finishRequested = true
    // Keep for later is explicit local retention; resuming it never sends an old message automatically.
    completion.intent.reset()
    completion.intent.choose(ConversationDictationDeliveryMode.PasteIntoDraft)
    recoveryHandedToComposer = true
    state = parked.failure
    return true
}

internal fun ConversationDictationController.composerRecovery(access: ConversationDictationComposerAccess): Failed? {
    val owned =
        access.sessionId == state.sessionId &&
            access.actionGeneration == notificationActionGeneration &&
            access.phase == ConversationDictationComposerPhase.RemainingAudio
    val failed = (state as? ConversationDictationState.Failed)?.takeIf { owned }
    return failed?.takeIf {
        composerAccess(it.target.accountRef, it.target.groupIdHex).phase ==
            ConversationDictationComposerPhase.RemainingAudio
    }
}
