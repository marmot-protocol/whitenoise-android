package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The mounted composer's attachment-aware send, offered to senders that do not go through the composer.
 *
 * Staged media and documents live in the conversation screen, so a sender that only has draft text
 * (dictation) would publish a text-only message and leave the attachments behind. Such a sender asks
 * [hasStagedAttachments] first and, when true, sends the complete message through [sendWithCaption].
 *
 * @param hasStaged whether the composer currently holds attachments that an ordinary Send would carry.
 * @param isBusy whether the composer is still preparing attachments or already sending a batch, in
 *   which case an ordinary Send is ignored and this one must refuse rather than race it. This is only an
 *   early refusal off the main thread, so [dispatch] must still claim the send itself (see
 *   [StagedAttachmentSendClaim]), because a send can start between this check and the dispatch.
 * @param dispatch starts the composer's own attachment send on the main thread and reports whether it was
 *   accepted, reporting false without sending when another send already owns the shelf.
 */
internal class StagedAttachmentSender(
    private val hasStaged: () -> Boolean,
    private val isBusy: () -> Boolean,
    private val dispatch: (caption: String, onResult: (Boolean) -> Unit) -> Unit,
) {
    /** Whether an ordinary Send from this composer would carry staged attachments. */
    fun hasStagedAttachments(): Boolean = hasStaged()

    /**
     * Sends the staged attachments with [caption] as one message.
     *
     * Returns true once the message is visibly pending, after calling [onPendingShown], and false when
     * nothing was published (busy, nothing staged, or rejected) so the caller can keep its draft. The
     * [isBusy] check here is a fast refusal, the authoritative claim is taken inside [dispatch] on the main thread.
     */
    suspend fun sendWithCaption(
        caption: String,
        onPendingShown: () -> Unit,
    ): Boolean {
        if (!hasStaged() || isBusy()) return false
        val result = CompletableDeferred<Boolean>()
        // The composer's send touches Compose state, so it starts on the main thread like a Send tap.
        withContext(Dispatchers.Main.immediate) {
            dispatch(caption) { accepted ->
                if (accepted) onPendingShown()
                result.complete(accepted)
            }
        }
        return result.await()
    }
}
