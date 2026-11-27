package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.withTimeoutOrNull

/** How long admission waits for a queued keystroke save before it gives up proving the caption's draft. */
private const val CAPTION_SAVE_WAIT_MS = 1_000L

/**
 * The saved native draft this send's caption can be cleared from once MDK accepts [token], or null when that cannot
 * be proved: the send no longer owns the composer text, or the draft does not hold exactly its caption. Only sends
 * the cleanup journal follows capture one. Queued keystrokes are given a moment to be written first, so the save it
 * names is the one the caption was read from, and any save after it, whatever it writes, leaves the caption alone.
 */
internal suspend fun ConversationController.captureSentCaption(
    account: String,
    retained: RetainedMediaUpload,
    token: String,
): CollectionSentCaption? {
    val caption = retained.caption?.trim().orEmpty()
    if (caption.isEmpty() || !retained.journalsDraftCleanup || !ownsDraftCaption(retained)) return null
    val groupIdHex = group.groupIdHex
    // A save still queued behind a long draft mutation is not waited out under the commit lock: the caption then
    // goes unproved and stays in the draft, which loses nothing.
    withTimeoutOrNull(CAPTION_SAVE_WAIT_MS) { appState.composerDraftExpansionBridge.flushText(account, groupIdHex) }
    val draft = appState.marmotIo(MarmotTraceSection.MEDIA_SEND) { selectedDraftOrNull(account, groupIdHex)?.draft }
    return draft
        ?.takeIf { it.content.trim() == caption && ownsDraftCaption(retained) }
        ?.let { CollectionSentCaption(token, it.createdAtMs, it.updatedAtMs) }
}

/**
 * Binds [caption] to the collection chunk sent under [token] before MDK is asked to accept it, replacing what an
 * earlier attempt of the same chunk captured. The record of another chunk's caption is left alone.
 */
internal suspend fun ConversationController.recordCollectionCaption(
    token: String,
    caption: CollectionSentCaption?,
) {
    val registry = appState.attachmentCollections
    val operation =
        registry.operations.value.values
            .firstOrNull { candidate -> !candidate.cleanupOnly && candidate.chunks.any { it.clientToken == token } }
    val recorded = operation?.sentCaption
    val replaces = caption != null || recorded?.clientToken == token
    if (operation != null && replaces && recorded != caption) {
        registry.updateDurably(operation.id) { it.copy(sentCaption = caption) }
    }
}

/** Whether a restart can settle this send's leftovers in its native draft from a journal record. */
internal val RetainedMediaUpload.journalsDraftCleanup: Boolean
    get() = draftHandling == RetainedDraftHandling.COLLECTION_CHUNK || tracksDraftAcceptance

/** Collection chunks already carry ownership in the collection journal. */
internal val RetainedMediaUpload.tracksDraftAcceptance: Boolean
    get() =
        draftHandling != RetainedDraftHandling.COLLECTION_CHUNK &&
            (
                isDraftStored ||
                    (
                        draftHandling == RetainedDraftHandling.COMPOSER_SELECTION &&
                            attachments.any { it.nativeDraftAttachmentId != null || it.nativeDraftFileBody != null }
                    )
            )

/** The native draft attachments this send was built from: stored files and inline rows alike. */
internal val RetainedMediaUpload.draftAttachmentIds: List<String>
    get() = attachments.mapNotNull { it.draftFile?.attachmentId ?: it.nativeDraftAttachmentId }

/**
 * Native draft attachments a send of this chat still owns: uploading, waiting for admission, or failed with its Retry.
 * The draft holds them until MDK accepts the send, so a shelf restored meanwhile, by reopening the chat or by another
 * cleanup, must not offer them for a second send. A reply keeps its picks on the shelf until it is accepted, so its
 * attachments are not named. Read on Main, where the retained sends live.
 */
internal fun WhiteNoiseAppState.sendOwnedDraftIds(
    account: String,
    group: String,
): Set<String> {
    val uploads = retainedMediaUploads(account, group)
    return activeUploadKeys(account, group).flatMapTo(HashSet()) { key ->
        val owner = uploads.get(key)
        if (owner == null || owner.replyTarget != null) emptyList() else owner.draftAttachmentIds
    }
}
