package dev.ipf.whitenoise.android.state

/**
 * Moves one chunk and its unsettled items to [chunkState] and [itemState]. A settled chunk keeps its state,
 * except that acceptance always wins: MDK may accept a chunk whose cancellation came too late, and that chunk
 * is sent, not cancelled. Published items therefore never move again and progress never runs backwards.
 */
internal fun AttachmentCollectionOperation.withChunkState(
    chunkId: String,
    chunkState: CollectionChunkState,
    itemState: CollectionItemState,
    nowMs: Long,
): AttachmentCollectionOperation {
    val accepting = chunkState == CollectionChunkState.ACCEPTED
    val chunk =
        chunks
            .firstOrNull { it.id == chunkId }
            ?.takeUnless { it.state == CollectionChunkState.ACCEPTED || (it.state.isSettled && !accepting) }
            ?: return this
    val ids = chunk.itemIds.toSet()
    return copy(
        chunks = chunks.map { if (it.id == chunkId) it.copy(state = chunkState) else it },
        items =
            items.map { item ->
                val movable = item.state != CollectionItemState.PUBLISHED && (accepting || !item.state.isSettled)
                if (item.id in ids && movable) item.copy(state = itemState) else item
            },
        updatedAtMs = nowMs,
    )
}

/** Records the MDK send token for [chunkId] before its upload starts, so a restart can ask MDK about it. */
internal fun AttachmentCollectionOperation.withChunkToken(
    chunkId: String,
    token: String,
    nowMs: Long,
): AttachmentCollectionOperation =
    copy(
        chunks = chunks.map { if (it.id == chunkId && !it.state.isSettled) it.copy(clientToken = token) else it },
        updatedAtMs = nowMs,
    )

/**
 * Settles items that will not be sent: [itemIds] become skipped, and a chunk left with nothing to send is
 * skipped with them. Used for unreadable picks and for the items of a failed chunk the user removed.
 */
internal fun AttachmentCollectionOperation.skipItems(
    itemIds: Set<String>,
    nowMs: Long,
): AttachmentCollectionOperation {
    val skipped =
        items.map { item ->
            if (item.id in itemIds && !item.state.isSettled) item.copy(state = CollectionItemState.SKIPPED) else item
        }
    val stateById = skipped.associate { it.id to it.state }
    return copy(
        items = skipped,
        chunks =
            chunks.map { chunk ->
                val allSkipped = chunk.itemIds.all { stateById[it] == CollectionItemState.SKIPPED }
                if (!chunk.state.isSettled && allSkipped) chunk.copy(state = CollectionChunkState.SKIPPED) else chunk
            },
        updatedAtMs = nowMs,
    )
}

/**
 * Cancels every chunk MDK has not accepted. A chunk still uploading stays as it is: if MDK accepts it after
 * all, [withChunkState] records it as sent; otherwise the runner settles it once its attempt ends.
 */
internal fun AttachmentCollectionOperation.cancelUnaccepted(nowMs: Long): AttachmentCollectionOperation {
    val cancelled =
        chunks.map { chunk ->
            val stoppable = !chunk.state.isSettled && chunk.state != CollectionChunkState.UPLOADING
            if (stoppable) chunk.copy(state = CollectionChunkState.CANCELLED) else chunk
        }
    val cancelledIds =
        cancelled.filter { it.state == CollectionChunkState.CANCELLED }.flatMapTo(mutableSetOf()) { it.itemIds }
    return copy(
        chunks = cancelled,
        items =
            items.map { item ->
                if (item.id in cancelledIds && !item.state.isSettled) {
                    item.copy(state = CollectionItemState.CANCELLED)
                } else {
                    item
                }
            },
        cancelRequested = true,
        paused = false,
        updatedAtMs = nowMs,
    )
}

/**
 * Prepares a restored operation to continue. Unreadable items are skipped and leave their chunks; a chunk
 * whose membership changed gets a new id from the next plan [generation], one whose members are intact keeps
 * its id and token, and the caption moves to the first unsent chunk if its original chunk is now empty.
 */
internal fun AttachmentCollectionOperation.resumedWithout(
    unrecoverable: Set<String>,
    generation: Int,
    nowMs: Long,
): AttachmentCollectionOperation {
    val trimmed = skipItems(unrecoverable, nowMs)
    val captionPending =
        trimmed.chunks.any { it.carriesCaption && !it.state.isSettled } ||
            chunks.any { it.carriesCaption && !it.state.isSettled }
    val replanned =
        trimmed.chunks.map { chunk ->
            val kept = chunk.itemIds.filterNot { it in unrecoverable }
            if (chunk.state.isSettled || kept == chunk.itemIds) {
                chunk
            } else {
                chunk.copy(id = collectionChunkId(id, generation, kept), itemIds = kept, clientToken = null)
            }
        }
    val captionOwner = replanned.firstOrNull { !it.state.isSettled }?.id.takeIf { captionPending }
    return trimmed.copy(
        chunks =
            replanned.map { chunk ->
                if (chunk.state.isSettled) chunk else chunk.copy(carriesCaption = chunk.id == captionOwner)
            },
        paused = false,
        unrecoverableItemIds = emptySet(),
        updatedAtMs = nowMs,
    )
}

/**
 * Returns an unsettled chunk to planned with its token cleared, so its next attempt is a fresh MDK
 * submission: MDK never admitted the old token, or the bytes a Retry would reuse are gone.
 */
internal fun AttachmentCollectionOperation.replanned(
    chunkId: String,
    nowMs: Long,
): AttachmentCollectionOperation =
    withChunkState(chunkId, CollectionChunkState.PLANNED, CollectionItemState.QUEUED, nowMs).let { next ->
        next.copy(
            chunks =
                next.chunks.map { chunk ->
                    if (chunk.id == chunkId && !chunk.state.isSettled) chunk.copy(clientToken = null) else chunk
                },
        )
    }
