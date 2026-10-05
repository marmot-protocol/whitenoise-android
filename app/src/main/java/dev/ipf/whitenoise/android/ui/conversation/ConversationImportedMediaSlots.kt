package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.whitenoise.android.share.ShareStreamStaging
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.appendPendingMediaSlots

/** A shelf refresh preserves occurrence identities, prepared previews and ordinary picker selections. */
internal fun restoreImportedMediaSlots(
    current: List<PendingMediaSlot>,
    restored: List<Uri>,
    maxItems: Int,
    owns: (Uri) -> Boolean,
): List<PendingMediaSlot> {
    val retained = current.filter { !owns(it.uri) || it.uri in restored }
    val added = restored.filterNot { uri -> retained.any { it.uri == uri } }
    return appendPendingMediaSlots(retained, added, maxItems)
}

/** Recover accepted sources without discarding bytes or changing an existing edited occurrence. */
internal fun restoreImportedComposerAttachments(
    currentMedia: List<PendingMediaSlot>,
    currentDocuments: List<Uri>,
    staging: ShareStreamStaging,
    owns: (Uri) -> Boolean,
): RestoredConversationAttachments {
    val allRestored = (staging.mediaUris + staging.documentUris).toSet()
    val existingMedia = currentMedia.filter { owns(it.uri) && it.uri in allRestored }.map { it.uri }
    val existingDocuments = currentDocuments.filter { owns(it) && it in allRestored }
    val media = (staging.mediaUris.filterNot { it in existingDocuments } + existingMedia).distinct()
    val documents = (staging.documentUris.filterNot { it in existingMedia } + existingDocuments).distinct()
    return RestoredConversationAttachments(
        restoreImportedMediaSlots(currentMedia, media, Int.MAX_VALUE, owns),
        (currentDocuments.filterNot(owns) + documents).distinct(),
    )
}

/** An over-limit recovery requires explicit removal; it must never silently drop a saved source. */
internal fun importedComposerExceedsLimit(
    media: List<PendingMediaSlot>,
    documents: List<Uri>,
    maxItems: Int,
    owns: (Uri) -> Boolean,
): Boolean {
    if (media.size + documents.size <= maxItems) return false
    return media.any { owns(it.uri) } || documents.any(owns)
}

/** Adds unique picks within remaining capacity without truncating an already recovered overflowing shelf. */
internal fun appendRecoveredDocuments(
    current: List<Uri>,
    added: List<Uri>,
    maxItems: Int,
): List<Uri> = current + added.filterNot { it in current }.distinct().take((maxItems - current.size).coerceAtLeast(0))

/** The native restore cannot discard platform sources published since its last input projection. */
internal fun mergeRestoredComposerAttachments(
    currentMedia: List<PendingMediaSlot>,
    currentDocuments: List<Uri>,
    restored: RestoredConversationAttachments,
    owns: (Uri) -> Boolean,
): RestoredConversationAttachments =
    RestoredConversationAttachments(
        mediaSlots = restored.mediaSlots.filterNot { owns(it.uri) } + currentMedia.filter { owns(it.uri) },
        documentUris = (restored.documentUris.filterNot(owns) + currentDocuments.filter(owns)).distinct(),
    )
