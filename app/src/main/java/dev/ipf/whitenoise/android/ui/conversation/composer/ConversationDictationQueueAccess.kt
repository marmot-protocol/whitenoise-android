package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.canQueueDictation
import dev.ipf.whitenoise.android.audio.followUpRecordingFor

/**
 * Whether this chat's composer may queue a recording behind dictations still being transcribed.
 *
 * The chat that owns the draining dictation continues through the dictation control instead.
 */
internal fun ConversationDictationController.queuesFromComposer(
    accountRef: String?,
    groupIdHex: String?,
): Boolean =
    accountRef != null &&
        groupIdHex != null &&
        !isOwnedBy(accountRef, groupIdHex) &&
        canQueueDictation(accountRef, groupIdHex)

/** Dictate starts when no dictation is in progress, or queues behind one from another chat. */
internal fun ConversationDictationController?.composerDictationOpen(
    accountRef: String?,
    groupIdHex: String?,
): Boolean =
    this == null ||
        (!completionControlsRequired && !blocksNewRequest) ||
        queuesFromComposer(accountRef, groupIdHex)

/** Toolbar commands wait for a pending dictation unless this chat can queue its own recording beside it. */
internal fun ConversationDictationController?.composerToolbarOpen(
    accountRef: String?,
    groupIdHex: String?,
): Boolean = this == null || !blocksNewRequest || queuesFromComposer(accountRef, groupIdHex)

/** A held Dictate gesture keeps the recording it opened in this chat, live or still waiting its turn. */
internal fun ConversationDictationController.heldCaptureOpenFor(
    accountRef: String?,
    groupIdHex: String?,
): Boolean {
    if (accountRef == null || groupIdHex == null) return false
    val activeHere = isOwnedBy(accountRef, groupIdHex) &&
        (captureInProgress || state is ConversationDictationState.CheckingProvider)
    return followUpRecordingFor(accountRef, groupIdHex) || activeHere
}
