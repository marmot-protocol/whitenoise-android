package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.whitenoise.android.share.ShareStreamStaging
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.appendPendingMediaSlots

/** Restores imported ordering while preserving edited occurrence identities and ordinary picker positions. */
internal fun restoreImportedMediaSlots(
    current: List<PendingMediaSlot>,
    restored: List<Uri>,
    maxItems: Int,
    owns: (Uri) -> Boolean,
): List<PendingMediaSlot> {
    val retained = current.filter { !owns(it.uri) || it.uri in restored }
    val added = restored.filterNot { uri -> retained.any { it.uri == uri } }
    val appended = appendPendingMediaSlots(retained, added, maxItems)
    val importedInOrder = appended.filter { owns(it.uri) }.sortedBy { restored.indexOf(it.uri) }.iterator()
    return appended.map { slot -> if (owns(slot.uri)) importedInOrder.next() else slot }
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

/** Adds missing native items without discarding later picks, current occurrence URIs or private ownership. */
internal fun mergeRestoredComposerAttachments(
    currentMedia: List<PendingMediaSlot>,
    currentDocuments: List<Uri>,
    restored: RestoredConversationAttachments,
    owns: (Uri) -> Boolean,
): RestoredConversationAttachments {
    val currentIds = currentMedia.map(PendingMediaSlot::id).toSet()
    val missingNative = restored.mediaSlots.filter { !owns(it.uri) && it.id !in currentIds }
    return RestoredConversationAttachments(
        mediaSlots = missingNative + currentMedia,
        documentUris = (restored.documentUris.filterNot(owns) + currentDocuments).distinct(),
    )
}
