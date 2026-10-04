package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.appendPendingMediaSlots

/** A shelf refresh preserves occurrence identities, prepared previews and ordinary picker selections. */
internal fun restoreImportedMediaSlots(
    current: List<PendingMediaSlot>,
    restored: List<Uri>,
    maxItems: Int,
    owns: (Uri) -> Boolean,
): List<PendingMediaSlot> {
    val retained = current.filter { !owns(it.uri) || it.uri in restored }.take(maxItems)
    val added = restored.filterNot { uri -> retained.any { it.uri == uri } }
    return appendPendingMediaSlots(retained, added, maxItems)
}

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
