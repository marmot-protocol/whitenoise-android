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
