package dev.ipf.whitenoise.android.ui.conversation.media

/** One staged attachment in the preview, in send order — media first, then documents. */
internal sealed class StagedPreviewItem {
    abstract val uri: android.net.Uri

    /** The pick whose draft's outgoing worker decodes this item: the slot id, or the document's URI. */
    abstract val pick: String

    data class Media(
        val slot: PendingMediaSlot,
    ) : StagedPreviewItem() {
        override val uri: android.net.Uri = slot.uri
        override val pick: String = slot.id
    }

    /** A staged document; [occurrence] tells repeated picks of one URI apart, starting at 1. */
    data class Document(
        override val uri: android.net.Uri,
        val occurrence: Int = 1,
    ) : StagedPreviewItem() {
        override val pick: String = uri.toString()
    }
}

/** The preview's items in send order, numbering repeated document URIs so each keeps its own key. */
internal fun stagedPreviewItems(
    mediaSlots: List<PendingMediaSlot>,
    documentUris: List<android.net.Uri>,
): List<StagedPreviewItem> {
    val media = mediaSlots.map { StagedPreviewItem.Media(it) }
    val seen = mutableMapOf<android.net.Uri, Int>()
    return media +
        documentUris.map { uri ->
            val occurrence = (seen[uri] ?: 0) + 1
            seen[uri] = occurrence
            StagedPreviewItem.Document(uri, occurrence)
        }
}

/**
 * Where the preview cursor lands after removing [removedIndex] from a list
 * that now holds [remainingCount] items. Removing an item before the cursor
 * shifts it left, removing the cursor itself keeps its slot (the next item
 * slides in), clamped to the new bounds.
 */
internal fun previewIndexAfterRemoval(
    removedIndex: Int,
    currentIndex: Int,
    remainingCount: Int,
): Int =
    when {
        remainingCount <= 0 -> 0
        removedIndex < currentIndex -> (currentIndex - 1).coerceIn(0, remainingCount - 1)
        else -> currentIndex.coerceIn(0, remainingCount - 1)
    }
