package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.audio.ConversationDictationSendRequest

/**
 * Publishes the composer's text with one engine call, the way the app sent before 0.10.0.
 *
 * Routing text through MDK's revision-safe draft cost three further calls — reading the selected
 * draft, re-reading it for attachments, and writing the revision — all inside the conversation's
 * commit lock and all ahead of the publish. The write also raced the composer's own debounced
 * draft writer, so the common "type, then send" case reached `MessageDraftRevisionConflict` and
 * fell back to this same direct send after paying for the round trip.
 *
 * Nothing is lost by skipping it: text typed after Send survives because the keystroke advances
 * the draft generation, which makes `beginSuccessfulSendCleanup` decline to delete the draft the
 * send did not carry. Media still submits its revision — see [sendComposerMedia] — because the
 * attachment descriptors have to match what MDK holds.
 */
internal suspend fun MarmotInterface.sendComposerText(
    accountRef: String,
    groupIdHex: String,
    replyTargetMessageIdHex: String?,
    text: String,
): SendSummaryFfi =
    if (replyTargetMessageIdHex != null) {
        replyToMessage(accountRef, groupIdHex, replyTargetMessageIdHex, text)
    } else {
        sendText(accountRef, groupIdHex, text)
    }

/** Publishes a completed non-reply dictation after its conversation UI controller has detached. */
internal suspend fun WhiteNoiseAppState.sendDetachedDictationText(request: ConversationDictationSendRequest): Boolean {
    val pendingClear = captureDraftForSend(request.accountRef, request.groupIdHex)
    val summary =
        withConversationTextSendOrder(request.accountRef, request.groupIdHex) {
            retryPendingConversationSend(validatedConnectivityRecoveryGeneration) {
                withGroupCommitLock(request.accountRef, request.groupIdHex) {
                    marmotIo(MarmotTraceSection.TEXT_SEND) {
                        sendComposerText(request.accountRef, request.groupIdHex, null, request.payload)
                    }
                }
            }
        }
    if (summary.messageIds.isEmpty()) return false
    pendingClear?.let(::clearDraftAfterSuccessfulSend)
    runCatching(request.onPendingShown)
    return true
}

/** Media variant of [sendComposerText]: the draft must describe every uploaded reference in order. */
@Suppress("ReturnCount") // Recovered ownership, matching draft admission, and legacy fallback are distinct outcomes.
internal suspend fun MarmotInterface.sendComposerMedia(
    accountRef: String,
    groupIdHex: String,
    references: List<MediaAttachmentReferenceFfi>,
    caption: String?,
    clientToken: String? = null,
): SendSummaryFfi {
    if (clientToken != null) recoveredLocalSend(accountRef, groupIdHex, clientToken)?.let { return it }
    val selected = selectedDraftOrNull(accountRef, groupIdHex)
    val content = selected?.draft
    if (selected != null && content != null && draftDescribes(content, references)) {
        val submitted =
            saveDraftForSend(accountRef, selected, caption.orEmpty(), content.replyToMessageIdHex)
                ?.let { revision ->
                    if (clientToken == null) {
                        sendDraftOrNull(accountRef, revision, references)
                    } else {
                        admitLocalSend(accountRef, groupIdHex, clientToken) {
                            sendMessageDraftWithClientToken(accountRef, revision, references, clientToken)
                        }
                    }
                }
        if (submitted != null) return submitted
    }
    check(clientToken == null) { "client-token media send requires a matching draft revision" }
    return sendMediaAttachments(accountRef, groupIdHex, references, caption)
}

/** Whether the selected draft's attachment descriptors line up one-to-one with the prepared references. */
internal fun draftDescribes(
    draft: SelectedMessageDraftContentFfi,
    references: List<MediaAttachmentReferenceFfi>,
): Boolean =
    draft.mediaAttachments.size == references.size &&
        draft.mediaAttachments.zip(references).all { (descriptor, reference) ->
            descriptor.fileName == reference.fileName && descriptor.mediaType == reference.mediaType
        }

/** Whether upload-ready plaintext has the exact ordered descriptors owned by the selected draft. */
internal fun draftDescribesUpload(
    draft: SelectedMessageDraftContentFfi,
    attachments: List<MediaUploadAttachmentRequestFfi>,
): Boolean =
    draft.mediaAttachments.size == attachments.size &&
        draft.mediaAttachments.zip(attachments).all { (descriptor, attachment) ->
            descriptor.fileName == attachment.fileName && descriptor.mediaType == attachment.mediaType
        }

@Suppress("SwallowedException") // A draft read failure only means the direct send path is used.
internal fun MarmotInterface.selectedDraftOrNull(
    accountRef: String,
    groupIdHex: String,
): SelectedMessageDraftFfi? =
    try {
        selectedMessageDraft(accountRef, groupIdHex)
    } catch (unavailable: MarmotKitException) {
        null
    }

/**
 * Makes the selected draft say exactly what is being sent, keeping attachments MDK already holds.
 * Returns the revision to submit, or null when there is no draft or it moved underneath the send.
 */
private fun MarmotInterface.saveDraftForSend(
    accountRef: String,
    selected: SelectedMessageDraftFfi,
    content: String,
    replyToMessageIdHex: String?,
): MessageDraftRevisionFfi? {
    val draft = selected.draft ?: return null
    return saveRevisionOrNull(accountRef, selected, draft, content, replyToMessageIdHex)
}

@Suppress("SwallowedException") // A revision conflict means another writer won; the caller falls back to a direct send.
private fun MarmotInterface.saveRevisionOrNull(
    accountRef: String,
    selected: SelectedMessageDraftFfi,
    draft: SelectedMessageDraftContentFfi,
    content: String,
    replyToMessageIdHex: String?,
): MessageDraftRevisionFfi? {
    if (draft.content == content && draft.replyToMessageIdHex == replyToMessageIdHex) return selected.revision
    val attachments = messageDraft(accountRef, draft.groupIdHex)?.mediaAttachments.orEmpty()
    return try {
        saveMessageDraftIfRevision(accountRef, selected.revision, content, replyToMessageIdHex, attachments).revision
    } catch (conflict: MarmotKitException.MessageDraftRevisionConflict) {
        null
    }
}

@Suppress("SwallowedException") // Conflicts and descriptor mismatches are handled by the direct-send fallback.
private suspend fun MarmotInterface.sendDraftOrNull(
    accountRef: String,
    revision: MessageDraftRevisionFfi,
    references: List<MediaAttachmentReferenceFfi>,
): SendSummaryFfi? =
    try {
        sendMessageDraft(accountRef, revision, references)
    } catch (conflict: MarmotKitException.MessageDraftRevisionConflict) {
        null
    } catch (invalid: MarmotKitException.InvalidMessageDraft) {
        null
    } catch (invalid: MarmotKitException.InvalidMediaReference) {
        null
    }
