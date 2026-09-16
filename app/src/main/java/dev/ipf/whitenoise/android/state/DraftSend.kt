package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.marmotkit.SendSummaryFfi

/**
 * Sends the composer's message through MDK's revision-safe draft when the selected draft describes
 * exactly what is being sent, so MDK clears only that revision on durable acceptance and later typing
 * survives. Anything the draft does not describe — a draft with staged media behind a text-only send,
 * media whose descriptors do not match the uploaded references, or a revision that changed underneath
 * the send — falls back to the direct send the app used before 0.10.0.
 */
internal suspend fun MarmotInterface.sendComposerText(
    accountRef: String,
    groupIdHex: String,
    replyTargetMessageIdHex: String?,
    text: String,
): SendSummaryFfi {
    val selected = selectedDraftOrNull(accountRef, groupIdHex)
    if (selected != null &&
        selected.draft
            ?.mediaAttachments
            .orEmpty()
            .isEmpty()
    ) {
        val submitted =
            saveDraftForSend(accountRef, selected, text, replyTargetMessageIdHex)
                ?.let { revision -> sendDraftOrNull(accountRef, revision, emptyList()) }
        if (submitted != null) return submitted
    }
    return if (replyTargetMessageIdHex != null) {
        replyToMessage(accountRef, groupIdHex, replyTargetMessageIdHex, text)
    } else {
        sendText(accountRef, groupIdHex, text)
    }
}

/** Media variant of [sendComposerText]: the draft must describe every uploaded reference in order. */
internal suspend fun MarmotInterface.sendComposerMedia(
    accountRef: String,
    groupIdHex: String,
    references: List<MediaAttachmentReferenceFfi>,
    caption: String?,
): SendSummaryFfi {
    val selected = selectedDraftOrNull(accountRef, groupIdHex)
    val content = selected?.draft
    if (selected != null && content != null && draftDescribes(content, references)) {
        val submitted =
            saveDraftForSend(accountRef, selected, caption.orEmpty(), content.replyToMessageIdHex)
                ?.let { revision -> sendDraftOrNull(accountRef, revision, references) }
        if (submitted != null) return submitted
    }
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

@Suppress("SwallowedException") // A draft read failure only means the direct send path is used.
private fun MarmotInterface.selectedDraftOrNull(
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
