package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.PrivateShareSendLease
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.AttachmentCollectionOperation
import dev.ipf.whitenoise.android.state.AttachmentCollectionRegistry
import dev.ipf.whitenoise.android.state.CollectionChunk
import dev.ipf.whitenoise.android.state.CollectionChunkState
import dev.ipf.whitenoise.android.state.CollectionItemKind
import dev.ipf.whitenoise.android.state.CollectionItemState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftSendClearToken
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.attachmentCollections
import dev.ipf.whitenoise.android.state.shouldAcceptMediaUploadForAccount
import dev.ipf.whitenoise.android.state.skipItems
import dev.ipf.whitenoise.android.state.withChunkState
import dev.ipf.whitenoise.android.state.withChunkToken
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendStaging
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.releaseUnadoptedStagedSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The state one running operation needs beyond its journaled metadata: the account session it started in,
 * the draft clear owed after chunk 1, and the bytes the composer had already prepared. None of it survives
 * the process, which is why a restored operation re-reads its items from their URIs.
 */
internal class CollectionRun(
    val operationId: String,
    val accountRef: String,
    val groupIdHex: String,
    val sessionEpoch: Long,
    val caption: String?,
    preparedImages: Map<String, PendingAttachment> = emptyMap(),
    preparedDocuments: Map<Uri, PendingAttachment> = emptyMap(),
    val draftClear: DraftSendClearToken? = null,
    val onFirstSeeded: () -> Unit = {},
) {
    private val images = java.util.concurrent.ConcurrentHashMap(preparedImages)
    private val documents = java.util.concurrent.ConcurrentHashMap(preparedDocuments)

    /** Whether this run already revealed its first queued chunk. */
    var revealed: Boolean = false

    /** The composer's prepared bytes for one visual item, if it had any. */
    fun preparedImage(itemId: String): PendingAttachment? = images[itemId]

    /** The composer's prepared bytes for one document URI, if it had any. */
    fun preparedDocument(uri: Uri): PendingAttachment? = documents[uri]

    /**
     * Drops the prepared bytes of a chunk once its queued send owns them, so a long operation never pins
     * every item's bytes until it ends; the controller's retained upload keeps what a Retry needs.
     */
    fun release(
        itemIds: Collection<String>,
        uris: Collection<Uri>,
    ) {
        itemIds.forEach(images::remove)
        uris.forEach(documents::remove)
    }
}

/** One chunk's attachments in send order, and the items that could not be prepared. */
private class PreparedChunk(
    val images: List<PendingAttachment>,
    val documents: List<PendingAttachment>,
    val skipped: Set<String>,
    val reason: Int?,
    val staged: Collection<StagedUploadSource>,
) {
    val isEmpty: Boolean
        get() = images.isEmpty() && documents.isEmpty()
}

/**
 * Sends a collection operation chunk by chunk through the composer's ordinary media path. Each chunk is
 * prepared only when its turn comes, so retained bytes never exceed one chunk, queued as one message, its MDK
 * token journaled, and only then uploaded. A failed chunk holds the rest until it is retried, skipped or the
 * operation is cancelled; acceptance by MDK, even after a late cancel, settles the chunk as sent.
 */
internal class AttachmentCollectionSender(
    private val owner: () -> ConversationMediaSender,
) {
    private val sender: ConversationMediaSender
        get() = owner()
    private val appState
        get() = sender.appState
    private val registry
        get() = appState.attachmentCollections

    /** Drives [run] until it finishes, is paused or disappears, and releases [lease] however it ends. */
    suspend fun drive(
        run: CollectionRun,
        lease: PrivateShareSendLease?,
    ) {
        try {
            while (step(run)) {
                // Each step waits or sends; the loop ends once the operation finishes, pauses or disappears.
            }
        } finally {
            lease?.let { withContext(NonCancellable + Dispatchers.IO) { it.release() } }
        }
    }

    /**
     * Takes the one action the next chunk needs now: wait on its queued send, wait for the user, or send it.
     * Returns false once the operation finished, paused or disappeared, or its account session ended.
     */
    private suspend fun step(run: CollectionRun): Boolean {
        val operation = registry.operations.value[run.operationId]?.takeUnless { it.paused }
        val chunk = operation?.nextChunk
        if (operation == null || chunk == null) return false
        val holds = sessionHolds(run)
        if (!holds) {
            // Another account or session now owns the app. Once MDK holds the caption chunk the record
            // stays for a later Resume, before that the composer's native draft still owns everything.
            if (operation.ownedByComposerDraft) {
                registry.discard(run.operationId)
            } else {
                registry.update(run.operationId) { it.copy(paused = true, updatedAtMs = registry.now()) }
            }
        }
        val key = chunk.clientToken?.let(::collectionChunkKey)
        val queued = key != null && appState.optimisticMessages(run.accountRef, run.groupIdHex)[key] != null
        when {
            !holds -> Unit
            queued -> awaitQueuedOutcome(run, chunk.id, requireNotNull(key))
            operation.cancelRequested -> settle(run, chunk.id, CollectionChunkState.CANCELLED)
            chunk.state == CollectionChunkState.FAILED -> awaitDecision(run, chunk.id)
            chunk.state == CollectionChunkState.UPLOADING && chunk.clientToken != null ->
                // Its queued send left without acceptance, so the user discarded it or the session dropped it.
                registry.update(run.operationId) { it.skipItems(chunk.itemIds.toSet(), registry.now()) }
            else -> sendChunk(run, operation, chunk)
        }
        return holds
    }

    /** Prepares, queues and uploads one chunk, recording its MDK token in the journal before the upload. */
    @Suppress("ReturnCount") // Withdrawal, nothing to send, a failed seed and an unrecorded token each stop here.
    private suspend fun sendChunk(
        run: CollectionRun,
        operation: AttachmentCollectionOperation,
        chunk: CollectionChunk,
    ) {
        persist(run) { op, now ->
            op.withChunkState(chunk.id, CollectionChunkState.PREPARING, CollectionItemState.PREPARING, now)
        }
        val prepared = prepareChunk(run, operation, chunk)
        if (!sessionHolds(run) || !registry.stillPreparing(run.operationId, chunk.id)) {
            // Cancelled, paused or dropped while its items were read: nothing of it is queued.
            releaseUnadoptedStagedSources(prepared.staged, emptySet())
            return
        }
        if (prepared.skipped.isNotEmpty()) {
            persist(run) { op, now -> op.skipItems(prepared.skipped, now) }
            appState.presentSkippedAttachments(prepared.skipped.size, prepared.reason)
        }
        val current = sender
        var seeded: ConversationController.QueuedAttachmentSend? = null
        try {
            if (prepared.isEmpty) return
            seeded =
                current
                    .seedPreparedAttachments(
                        ConversationMediaSender.PreparedStagedAttachments(
                            images = prepared.images,
                            documents = DocumentReadOutcome(prepared.documents, emptySet(), false, 0L),
                            imageOverflowed = false,
                            visualFailureToast = R.string.toast_couldnt_decode_image,
                        ),
                        run.caption.takeIf { chunk.carriesCaption },
                        SystemClock.elapsedRealtime(),
                        replyTarget = null,
                        replyVersion = 0L,
                    ).singleOrNull()
        } finally {
            val adopted = adoptedStagedSources(current.controller, listOfNotNull(seeded))
            releaseUnadoptedStagedSources(prepared.staged, adopted)
        }
        // A send seeded just as the session ended still goes to uploadQueued, whose own guard withdraws it.
        val queued = seeded
        if (queued == null) {
            persist(run) { op, now ->
                op.withChunkState(chunk.id, CollectionChunkState.FAILED, CollectionItemState.FAILED, now)
            }
            return
        }
        val recorded = registry.recordChunkToken(run.operationId, chunk.id, queued.tempId)
        // A cancel, pause or discard that landed while the send was being seeded leaves the chunk without this
        // token, so the send is withdrawn before any upload, as is one whose token the journal could not hold.
        // The live operation, not the recorded snapshot: a discard may have landed while the write was awaited.
        val live = recorded?.let { registry.operations.value[run.operationId] }
        val owned = live?.chunks?.firstOrNull { it.id == chunk.id }
        val claimed = owned?.state == CollectionChunkState.UPLOADING && owned.clientToken == queued.tempId
        if (!claimed || live.cancelRequested) {
            val storageFailed = recorded == null && registry.operations.value[run.operationId] != null
            return appState.withdrawUnrecorded(current.controller, queued, run.operationId, chunk.id, storageFailed)
        }
        run.release(chunk.itemIds, operation.items.filter { it.id in chunk.itemIds }.map { Uri.parse(it.uri) })
        if (!run.revealed) {
            run.revealed = true
            run.onFirstSeeded()
        }
        current.controller.uploadQueued(queued) { onChunkAccepted(run, chunk) }
    }

    /** Records a durable acceptance, and clears the composer draft once the caption-bearing chunk is MDK's. */
    private fun onChunkAccepted(
        run: CollectionRun,
        chunk: CollectionChunk,
    ) {
        registry.update(run.operationId) {
            it.withChunkState(chunk.id, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, registry.now())
        }
        if (chunk.carriesCaption) run.draftClear?.let(appState::clearDraftAfterSuccessfulSend)
    }

    /**
     * Reads each item of [chunk] on its own, so an unreadable or oversized pick is named and skipped instead
     * of failing the album, and keeps the chunk's in-memory bytes under the retained-upload ceiling.
     */
    private suspend fun prepareChunk(
        run: CollectionRun,
        operation: AttachmentCollectionOperation,
        chunk: CollectionChunk,
    ): PreparedChunk {
        val images = mutableListOf<PendingAttachment>()
        val documents = mutableListOf<PendingAttachment>()
        val skipped = linkedSetOf<String>()
        var reason: Int? = null
        var inMemoryBytes = 0L
        val staged = ConcurrentLinkedQueue<StagedUploadSource>()
        // An item already skipped stays in its chunk's membership but is never read or sent again.
        val pending =
            chunk.itemIds.mapNotNull { id -> operation.items.firstOrNull { it.id == id } }.filterNot {
                it.state.isSettled
            }
        var completed = false
        try {
            pending.forEach { item ->
                val uri = Uri.parse(item.uri)
                val media = item.kind == CollectionItemKind.MEDIA
                val prepared =
                    sender.prepareStagedAttachments(
                        imageSlots = if (media) listOf(PendingMediaSlot(item.id, uri)) else emptyList(),
                        documentUris = if (media) emptyList() else listOf(uri),
                        preparedImageAttachments =
                            listOfNotNull(run.preparedImage(item.id)?.let { item.id to it }).toMap(),
                        preparedDocumentAttachments =
                            listOfNotNull(run.preparedDocument(uri)?.let { uri to it }).toMap(),
                        allowFileBacked = true,
                        staging = FileBackedSendStaging { staged += it },
                    )
                val attachment = (prepared.images + prepared.documents.attachments).singleOrNull()
                val bytes = attachment?.inMemoryBytes?.size?.toLong() ?: 0L
                if (attachment == null || inMemoryBytes + bytes > ConversationController.MEDIA_RETAINED_MAX_BYTES) {
                    skipped += item.id
                    reason = reason ?: prepared.refusalMessage(attachment != null)
                } else {
                    inMemoryBytes += bytes
                    if (media) images += attachment else documents += attachment
                }
            }
            val withThumbhashes = addMissingThumbhashes(documents)
            completed = true
            return PreparedChunk(images, withThumbhashes, skipped, reason, staged.toList())
        } finally {
            // Copies made before a failure or cancellation have no owner yet, so they are released here.
            if (!completed) withContext(NonCancellable) { releaseUnadoptedStagedSources(staged.toList(), emptySet()) }
        }
    }

    /**
     * Mirrors one queued chunk's bubble into the operation until MDK accepts it, the bubble is discarded, or
     * the operation is cancelled while the bubble shows a failure. A failed bubble keeps blocking later chunks.
     */
    private suspend fun awaitQueuedOutcome(
        run: CollectionRun,
        chunkId: String,
        key: String,
    ) {
        val optimistic = appState.optimisticMessages(run.accountRef, run.groupIdHex)
        val outcome =
            combine(
                snapshotFlow { optimistic[key]?.status },
                registry.operations.map { it[run.operationId] },
            ) { status, operation -> status to operation }.first { (status, operation) ->
                val chunk = operation?.chunks?.firstOrNull { it.id == chunkId }
                // A chunk replanned for a fresh send no longer owns this bubble, so its state is not copied back.
                val owned = chunk?.clientToken?.let(::collectionChunkKey) == key
                if (chunk != null && owned && !chunk.state.isSettled) mirrorBubble(run, chunk, status)
                chunk == null ||
                    chunk.state.isSettled ||
                    status == null ||
                    operation.paused ||
                    (operation.cancelRequested && status == MessageStatus.Failed)
            }
        val operation = outcome.second
        // A chunk retried afresh carries a new token, so this bubble's departure says nothing about it.
        val chunk =
            operation
                ?.chunks
                ?.firstOrNull { it.id == chunkId }
                ?.takeUnless { it.state.isSettled || it.clientToken?.let(::collectionChunkKey) != key }
        if (operation == null || chunk == null) return
        if (outcome.first == null) {
            // The bubble left without acceptance: the user discarded it, so its items are not sent.
            registry.update(run.operationId) { it.skipItems(chunk.itemIds.toSet(), registry.now()) }
        } else if (operation.cancelRequested) {
            settle(run, chunkId, CollectionChunkState.CANCELLED)
        }
    }

    /** Copies a queued bubble's Pending or Failed state onto its chunk, writing only real changes. */
    private fun mirrorBubble(
        run: CollectionRun,
        chunk: CollectionChunk,
        status: MessageStatus?,
    ) {
        val target =
            when (status) {
                MessageStatus.Failed -> CollectionChunkState.FAILED
                MessageStatus.Pending -> CollectionChunkState.UPLOADING
                else -> null
            }
        if (target == null || target == chunk.state) return
        val itemState =
            if (target == CollectionChunkState.FAILED) CollectionItemState.FAILED else CollectionItemState.UPLOADING
        registry.update(run.operationId) { it.withChunkState(chunk.id, target, itemState, registry.now()) }
    }

    /** Waits for the user to retry, skip or cancel a failed chunk that has no queued send to retry. */
    private suspend fun awaitDecision(
        run: CollectionRun,
        chunkId: String,
    ) {
        registry.operations
            .map { it[run.operationId] }
            .first { operation ->
                val chunk = operation?.chunks?.firstOrNull { it.id == chunkId }
                operation == null ||
                    operation.paused ||
                    operation.cancelRequested ||
                    chunk?.state != CollectionChunkState.FAILED
            }
    }

    /** Settles [chunkId] as cancelled; acceptance has already been recorded if MDK took it. */
    private fun settle(
        run: CollectionRun,
        chunkId: String,
        state: CollectionChunkState,
    ) {
        registry.update(run.operationId) {
            it.withChunkState(chunkId, state, CollectionItemState.CANCELLED, registry.now())
        }
    }

    /** Whether the account session that started [run] still owns the app. */
    private fun sessionHolds(run: CollectionRun): Boolean =
        shouldAcceptMediaUploadForAccount(
            run.accountRef,
            run.sessionEpoch,
            appState.activeAccountRef,
            appState.mediaUploadSessionEpoch(),
        )

    /** Applies [transform] to the run's operation; its journal write is queued off the main thread. */
    private fun persist(
        run: CollectionRun,
        transform: (AttachmentCollectionOperation, Long) -> AttachmentCollectionOperation,
    ) {
        registry.update(run.operationId) { transform(it, registry.now()) }
    }
}

/**
 * Withdraws a queued chunk that must not upload, before any upload. When [storageFailed], the journal could not
 * record its token, so a restart could not tell whether MDK took it: the chunk fails, so the user can retry it
 * once storage is writable again. Otherwise the chunk was cancelled or its operation ended, and nothing is shown.
 */
private suspend fun dev.ipf.whitenoise.android.state.WhiteNoiseAppState.withdrawUnrecorded(
    controller: ConversationController,
    queued: ConversationController.QueuedAttachmentSend,
    operationId: String,
    chunkId: String,
    storageFailed: Boolean,
) {
    controller.deleteMessage(queued.optimistic, presentFailure = false, optimisticKeyOverride = queued.key)
    if (!storageFailed) return
    val registry = attachmentCollections
    withContext(Dispatchers.IO) {
        registry.update(operationId) {
            it.withChunkState(chunkId, CollectionChunkState.FAILED, CollectionItemState.FAILED, registry.now())
        }
    }
    present(R.string.share_import_storage)
}

/** Records [token] on [chunkId] and moves it to uploading, waiting until the journal holds both. */
private suspend fun AttachmentCollectionRegistry.recordChunkToken(
    operationId: String,
    chunkId: String,
    token: String,
): AttachmentCollectionOperation? =
    updateDurably(operationId) { op ->
        val now = now()
        op
            .withChunkToken(chunkId, token, now)
            .withChunkState(chunkId, CollectionChunkState.UPLOADING, CollectionItemState.UPLOADING, now)
    }

/** Whether [chunkId] may still be queued: its operation is live, not cancelled or paused, and still on it. */
private fun AttachmentCollectionRegistry.stillPreparing(
    operationId: String,
    chunkId: String,
): Boolean {
    val live = operations.value[operationId]?.takeUnless { it.cancelRequested || it.paused }
    return live?.chunks?.firstOrNull { it.id == chunkId }?.state == CollectionChunkState.PREPARING
}

/** Tells the user how many picks of a chunk were not sent, with the first reason as detail. */
internal fun dev.ipf.whitenoise.android.state.WhiteNoiseAppState.presentSkippedAttachments(
    count: Int,
    reason: Int?,
) {
    val title = appContext.resources.getQuantityString(R.plurals.attachment_collection_skipped, count, count)
    presentText(AppText.Plain(title), reason?.let { AppText.Resource(it) })
}

/** The controller's optimistic key for a queued send whose MDK token is [token]. */
internal fun collectionChunkKey(token: String): String = "msg:$token"

/** The message that best explains why one prepared pick produced nothing, or did not fit the chunk. */
private fun ConversationMediaSender.PreparedStagedAttachments.refusalMessage(fitIssue: Boolean): Int =
    when {
        fitIssue || imageOverflowed || documents.albumOverflowed -> R.string.media_album_too_large
        imageStorageUnavailable -> R.string.share_import_storage
        documents.failures.isNotEmpty() -> documents.failures.first().messageResource
        else -> visualFailureToast
    }

private val boundCollectionSenders =
    java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<ConversationMediaSender>>()

/**
 * Makes [sender] the one later chunks of its conversation use, so an operation started before rotation or
 * activity recreation keeps queuing into the screen the user now sees.
 */
internal fun bindCollectionSender(sender: ConversationMediaSender) {
    val account = sender.controller.boundAccountRef ?: return
    boundCollectionSenders["$account|${sender.controller.group.groupIdHex}"] = java.lang.ref.WeakReference(sender)
}

/** The most recently bound sender for a conversation, while its screen is still reachable. */
internal fun latestCollectionSender(
    accountRef: String,
    groupIdHex: String,
): ConversationMediaSender? = boundCollectionSenders["$accountRef|$groupIdHex"]?.get()
