package dev.ipf.whitenoise.android.state

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Most media and document occurrences one composer operation may hold, across every intake path (#2576). */
internal const val ATTACHMENT_COLLECTION_MAX_ITEMS = 100

/**
 * Most attachments one chunk carries in a single message. MarmotKit publishes no event-size limit, so this
 * keeps every message at the album size the composer has always sent, well inside the event envelope.
 */
internal const val ATTACHMENT_CHUNK_MAX_ITEMS = 10

/** Whether an occurrence came from the visual album or the document shelf; documents travel one per message. */
internal enum class CollectionItemKind {
    MEDIA,
    DOCUMENT,
}

/** Where one occurrence stands in a collection send. Published, cancelled and skipped never change again. */
internal enum class CollectionItemState {
    QUEUED,
    PREPARING,
    UPLOADING,
    PUBLISHED,
    FAILED,
    CANCELLED,
    SKIPPED,
    ;

    /** True once the item can no longer be sent or retried by this operation. */
    val isSettled: Boolean
        get() = this == PUBLISHED || this == CANCELLED || this == SKIPPED
}

/** Where one chunk stands. Accepted means MDK durably owns its message, whether or not relays have it yet. */
internal enum class CollectionChunkState {
    PLANNED,
    PREPARING,
    UPLOADING,
    ACCEPTED,
    FAILED,
    CANCELLED,
    SKIPPED,
    ;

    /** True once nothing in the chunk can be sent again. */
    val isSettled: Boolean
        get() = this == ACCEPTED || this == CANCELLED || this == SKIPPED
}

/** One staged occurrence: [id] is the composer's stable occurrence id, so duplicate URIs stay distinct. */
internal data class CollectionItem(
    val id: String,
    val uri: String,
    val kind: CollectionItemKind,
    val state: CollectionItemState = CollectionItemState.QUEUED,
)

/**
 * One planned message. [id] is derived from the operation and its ordered item ids, so the same plan
 * always yields the same chunk ids. [clientToken] is the MDK send token, recorded before upload starts.
 */
internal data class CollectionChunk(
    val id: String,
    val index: Int,
    val itemIds: List<String>,
    val carriesCaption: Boolean,
    val clientToken: String? = null,
    val state: CollectionChunkState = CollectionChunkState.PLANNED,
)

/** An item's size as the planner sees it; [bytes] is null when the provider would not say. */
internal data class PlannedCollectionItem(
    val id: String,
    val kind: CollectionItemKind,
    val bytes: Long?,
)

/** What the composer offers to a collection operation, before any chunk exists. */
internal data class CollectionPlanLimits(
    val maxChunkBytes: Long = ConversationController.MEDIA_RETAINED_MAX_BYTES,
    val maxItemsPerChunk: Int = ATTACHMENT_CHUNK_MAX_ITEMS,
)

/**
 * Splits ordered items into ordered chunks. Visual items share an album until the next one would pass
 * [CollectionPlanLimits.maxChunkBytes] or [CollectionPlanLimits.maxItemsPerChunk]; a document, an item larger
 * than the byte bound, or one of unknown size travels alone. Only chunk 0 carries the caption. The result
 * depends only on the inputs, so a restored operation replans to identical chunk ids.
 */
internal fun planAttachmentChunks(
    operationId: String,
    items: List<PlannedCollectionItem>,
    limits: CollectionPlanLimits = CollectionPlanLimits(),
    generation: Int = 0,
): List<CollectionChunk> {
    val groups = mutableListOf<List<String>>()
    var album = mutableListOf<String>()
    var albumBytes = 0L
    val flush = {
        if (album.isNotEmpty()) groups += album.toList()
        album = mutableListOf()
        albumBytes = 0L
    }
    items.forEach { item ->
        val bytes = item.bytes
        val travelsAlone = item.kind == CollectionItemKind.DOCUMENT || bytes == null || bytes > limits.maxChunkBytes
        if (travelsAlone) {
            flush()
            groups += listOf(item.id)
        } else {
            if (album.size >= limits.maxItemsPerChunk || albumBytes + bytes > limits.maxChunkBytes) flush()
            album += item.id
            albumBytes += bytes
        }
    }
    flush()
    return groups.mapIndexed { index, ids ->
        CollectionChunk(
            id = collectionChunkId(operationId, generation, ids),
            index = index,
            itemIds = ids,
            carriesCaption = index == 0,
        )
    }
}

/** A name-based UUID over the operation, plan generation and ordered item ids. */
internal fun collectionChunkId(
    operationId: String,
    generation: Int,
    itemIds: List<String>,
): String {
    val seed =
        buildString {
            append(operationId).append('\u0000').append(generation)
            itemIds.forEach { append('\u0000').append(it) }
        }
    return UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
}

/** Why a collection is no longer moving forward on its own. */
internal enum class CollectionOperationPhase {
    /** Chunks are being prepared or sent in order. */
    RUNNING,

    /** A failed chunk holds every later chunk until it is retried, skipped or the operation is cancelled. */
    BLOCKED,

    /** Restored after the process ended; nothing is sent until the user resumes or discards it. */
    PAUSED,

    /** Every chunk is accepted, cancelled or skipped. */
    FINISHED,
}

/**
 * One user-initiated send of up to [ATTACHMENT_COLLECTION_MAX_ITEMS] occurrences into one conversation.
 * Everything here is metadata: ids, provider URIs, states and MDK send tokens, never attachment bytes.
 * [unrecoverableItemIds] lists items a restored operation can no longer read and so will not send.
 */
internal data class AttachmentCollectionOperation(
    val id: String,
    val accountRef: String,
    val groupIdHex: String,
    val items: List<CollectionItem>,
    val chunks: List<CollectionChunk>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val paused: Boolean = false,
    val unrecoverableItemIds: Set<String> = emptySet(),
    val cancelRequested: Boolean = false,
) {
    /** Every item the operation started with; it never shrinks, so progress cannot run backwards. */
    val total: Int
        get() = items.size

    /** Items whose message MDK has accepted. */
    val published: Int
        get() = items.count { it.state == CollectionItemState.PUBLISHED }

    /** Items that will not change again, the numerator of the progress bar. */
    val settled: Int
        get() = items.count { it.state.isSettled }

    /** The first chunk that is not yet settled; chunks are sent strictly in order. */
    val nextChunk: CollectionChunk?
        get() = chunks.firstOrNull { !it.state.isSettled }

    /** The operation's coarse state for presentation and the runner. */
    val phase: CollectionOperationPhase
        get() =
            when {
                nextChunk == null -> CollectionOperationPhase.FINISHED
                paused -> CollectionOperationPhase.PAUSED
                nextChunk?.state == CollectionChunkState.FAILED -> CollectionOperationPhase.BLOCKED
                else -> CollectionOperationPhase.RUNNING
            }
}

/**
 * Stable occurrence ids for the document shelf, which holds plain URIs: the nth copy of a URI always gets
 * the same id, so a document keeps its identity across reorder, removal of other items and restoration.
 */
internal fun collectionDocumentIds(uris: List<String>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return uris.map { uri ->
        val ordinal = (seen[uri] ?: 0) + 1
        seen[uri] = ordinal
        "doc-" + UUID.nameUUIDFromBytes("$uri\u0000$ordinal".toByteArray(StandardCharsets.UTF_8))
    }
}
