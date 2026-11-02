package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.share.PrivateShareSendLease
import dev.ipf.whitenoise.android.state.AttachmentCollectionOperation
import dev.ipf.whitenoise.android.state.CollectionChunkState
import dev.ipf.whitenoise.android.state.CollectionItem
import dev.ipf.whitenoise.android.state.CollectionItemKind
import dev.ipf.whitenoise.android.state.CollectionItemState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftSendClearToken
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.PlannedCollectionItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentCollections
import dev.ipf.whitenoise.android.state.cancelUnaccepted
import dev.ipf.whitenoise.android.state.claimCollectionResume
import dev.ipf.whitenoise.android.state.collectionDocumentIds
import dev.ipf.whitenoise.android.state.planAttachmentChunks
import dev.ipf.whitenoise.android.state.releaseCollectionResume
import dev.ipf.whitenoise.android.state.replanned
import dev.ipf.whitenoise.android.state.resumedWithout
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.skipItems
import dev.ipf.whitenoise.android.state.withChunkState
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.queryContentSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** What one Send hands to a collection operation; [caption] is already trimmed and null when blank. */
internal data class CollectionSendRequest(
    val imageSlots: List<PendingMediaSlot>,
    val documentUris: List<Uri>,
    val caption: String?,
    val preparedImages: Map<String, PendingAttachment> = emptyMap(),
    val preparedDocuments: Map<Uri, PendingAttachment> = emptyMap(),
    val draftClear: DraftSendClearToken? = null,
)

/** What MDK knows about a chunk token after the process that recorded it ended. */
internal enum class CollectionTokenStatus {
    /** MDK owns the message: published, queued, or engine-owned. */
    ACCEPTED,

    /** MDK never admitted the token, or refused it; the chunk must be sent again. */
    NOT_ACCEPTED,

    /** The probe failed, so nothing is known; the chunk and its token stay as they are until asked again. */
    UNRESOLVED,
}

/** Asks MDK for a token's local send status; MDK having no record means not accepted, a failed probe unresolved. */
internal fun WhiteNoiseAppState.mdkCollectionTokenStatus(
    accountRef: String,
    groupIdHex: String,
): suspend (String) -> CollectionTokenStatus? =
    { token ->
        runCatchingCancellable {
            when (marmotIo { localSendStatus(accountRef, groupIdHex, token) }) {
                is LocalSendStatusFfi.Completed, LocalSendStatusFfi.Queued, LocalSendStatusFfi.EngineOwned ->
                    CollectionTokenStatus.ACCEPTED
                LocalSendStatusFfi.Rejected, null -> CollectionTokenStatus.NOT_ACCEPTED
            }
        }.getOrDefault(CollectionTokenStatus.UNRESOLVED)
    }

/**
 * Starts a collection operation for a non-reply Send of more than one item. Private share sources are leased
 * for the whole operation, the plan is journaled, and only then does the shelf settle and chunk 1 begin.
 * [onAfterSend] runs once, when the first chunk's bubble appears.
 */
internal fun ConversationMediaSender.startCollectionSend(
    request: CollectionSendRequest,
    completion: StagedMediaSendCompletion,
    onAfterSend: () -> Unit,
) {
    val account = controller.boundAccountRef ?: return completion.reject()
    val groupIdHex = controller.group.groupIdHex
    val sessionEpoch = appState.mediaUploadSessionEpoch()
    val sourceAccount = appState.accounts.firstOrNull { it.label == account }?.accountIdHex
    val original = this
    appState.launchMutation {
        var lease: PrivateShareSendLease? = null
        var started = false
        try {
            val uris = request.imageSlots.map { it.uri } + request.documentUris
            lease =
                acquireStagedSources(context, uris, sourceAccount).getOrElse {
                    // Without the lease, stale cleanup could delete private sources before their chunk is read.
                    return@launchMutation appState.present(R.string.share_import_storage)
                }
            val operation =
                withContext(Dispatchers.IO) {
                    planCollectionOperation(context, account, groupIdHex, request, appState.attachmentCollections.now())
                }
            started = withContext(Dispatchers.IO) { appState.attachmentCollections.start(operation) }
            if (!started) return@launchMutation appState.present(R.string.share_import_storage)
            completion.accept()
            val run =
                CollectionRun(
                    operation.id,
                    account,
                    groupIdHex,
                    sessionEpoch,
                    request.caption,
                    request.preparedImages,
                    request.preparedDocuments,
                    request.draftClear,
                    onAfterSend,
                )
            val held = lease.also { lease = null }
            AttachmentCollectionSender { latestCollectionSender(account, groupIdHex) ?: original }.drive(run, held)
        } finally {
            lease?.let { withContext(NonCancellable + Dispatchers.IO) { it.release() } }
            if (!started) completion.reject()
        }
    }
}

/** Builds the journaled operation: stable item ids in shelf order, and a byte-bounded deterministic chunk plan. */
internal fun planCollectionOperation(
    context: Context,
    accountRef: String,
    groupIdHex: String,
    request: CollectionSendRequest,
    nowMs: Long,
): AttachmentCollectionOperation {
    val resolver = context.contentResolver
    val sized = { prepared: PendingAttachment?, uri: Uri ->
        prepared?.byteCount ?: queryContentSize(resolver, uri).takeIf { it >= 0L }
    }
    val documentIds = collectionDocumentIds(request.documentUris.map(Uri::toString))
    val media =
        request.imageSlots.map { slot ->
            CollectionItem(slot.id, slot.uri.toString(), CollectionItemKind.MEDIA) to
                sized(request.preparedImages[slot.id], slot.uri)
        }
    val documents =
        request.documentUris.mapIndexed { index, uri ->
            CollectionItem(documentIds[index], uri.toString(), CollectionItemKind.DOCUMENT) to
                sized(request.preparedDocuments[uri], uri)
        }
    val all = media + documents
    val id = UUID.randomUUID().toString()
    return AttachmentCollectionOperation(
        id = id,
        accountRef = accountRef,
        groupIdHex = groupIdHex,
        items = all.map { it.first },
        chunks = planAttachmentChunks(id, all.map { (item, size) -> PlannedCollectionItem(item.id, item.kind, size) }),
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
    )
}

/** Whether MDK accepted no chunk yet, so the composer's native draft still owns the selection and caption. */
internal val AttachmentCollectionOperation.ownedByComposerDraft: Boolean
    get() = chunks.none { it.state == CollectionChunkState.ACCEPTED }

/**
 * Asks MDK about every unsettled chunk that recorded a token and has no queued send in this process. Accepted
 * tokens settle as sent; any other chunk returns to planned with its token cleared, so it is sent afresh.
 * A probe that fails leaves the chunk as it is.
 */
internal suspend fun reconcileCollectionTokens(
    operation: AttachmentCollectionOperation,
    stillQueued: (String) -> Boolean,
    tokenStatus: suspend (String) -> CollectionTokenStatus?,
    nowMs: Long,
): AttachmentCollectionOperation {
    val statuses = collectionTokenStatuses(operation, stillQueued, tokenStatus)
    return operation.withTokenStatuses(statuses, nowMs)
}

/** MDK's answer for each recorded token of an unsettled chunk not queued here; a failed probe is unresolved. */
internal suspend fun collectionTokenStatuses(
    operation: AttachmentCollectionOperation,
    stillQueued: (String) -> Boolean,
    tokenStatus: suspend (String) -> CollectionTokenStatus?,
): Map<String, CollectionTokenStatus> =
    operation.chunks
        .filter { !it.state.isSettled }
        .mapNotNull { it.clientToken?.takeUnless(stillQueued) }
        .associateWith { token -> tokenStatus(token) ?: CollectionTokenStatus.UNRESOLVED }

/** Applies MDK's answers to the chunks that still hold the probed tokens; any other chunk is left alone. */
internal fun AttachmentCollectionOperation.withTokenStatuses(
    statuses: Map<String, CollectionTokenStatus>,
    nowMs: Long,
): AttachmentCollectionOperation =
    chunks.filter { !it.state.isSettled }.fold(this) { result, chunk ->
        when (chunk.clientToken?.let(statuses::get)) {
            CollectionTokenStatus.ACCEPTED ->
                result.withChunkState(chunk.id, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, nowMs)
            CollectionTokenStatus.NOT_ACCEPTED -> result.replanned(chunk.id, nowMs)
            CollectionTokenStatus.UNRESOLVED, null -> result
        }
    }

/**
 * Recovers this conversation's journaled operations after the process ended. An operation MDK accepted no
 * chunk of is dropped: the composer's native draft still owns that selection and caption, and sending it
 * from here too would duplicate it. Any other is shown paused, naming the items that can no longer be read.
 */
internal suspend fun WhiteNoiseAppState.restoreCollectionOperations(
    accountRef: String,
    groupIdHex: String,
    readable: (String) -> Boolean,
    tokenStatus: suspend (String) -> CollectionTokenStatus? = mdkCollectionTokenStatus(accountRef, groupIdHex),
) {
    val registry = attachmentCollections
    withContext(Dispatchers.IO) { registry.journaledFor(accountRef, groupIdHex) }.forEach { journaled ->
        val statuses = collectionTokenStatuses(journaled, { false }, tokenStatus)
        val reconciled = journaled.withTokenStatuses(statuses, registry.now())
        // A token MDK could not be asked about may already be sent, so its record is kept for another try.
        val unresolved = CollectionTokenStatus.UNRESOLVED in statuses.values
        val unrecoverable =
            withContext(Dispatchers.IO) {
                reconciled.items
                    .filter { !it.state.isSettled && (it.uri.isBlank() || !readable(it.uri)) }
                    .mapTo(linkedSetOf()) { it.id }
            }
        if (!unresolved && (reconciled.ownedByComposerDraft || reconciled.nextChunk == null)) {
            withContext(Dispatchers.IO) { registry.adopt(reconciled) }
            registry.discard(reconciled.id)
        } else {
            registry.adopt(reconciled.copy(paused = true, unrecoverableItemIds = unrecoverable))
        }
    }
}

/**
 * Continues a paused operation with the same stable ids: MDK is asked about recorded tokens again, unreadable
 * items are skipped and named, and the remaining chunks are sent in order from their URIs.
 */
internal fun ConversationMediaSender.resumeCollection(
    operationId: String,
    tokenStatus: (suspend (String) -> CollectionTokenStatus?)? = null,
) {
    val registry = appState.attachmentCollections
    val paused = registry.operations.value[operationId]?.takeIf { it.paused } ?: return
    // Claimed before the first suspension, so a second tap cannot start a second runner for the same chunks.
    if (!claimCollectionResume(operationId)) return
    val sessionEpoch = appState.mediaUploadSessionEpoch()
    val original = this
    appState.launchMutation {
        var skipped = 0
        val updated =
            try {
                val optimistic = appState.optimisticMessages(paused.accountRef, paused.groupIdHex)
                val statuses =
                    collectionTokenStatuses(
                        paused,
                        { token -> optimistic[collectionChunkKey(token)] != null },
                        tokenStatus ?: appState.mdkCollectionTokenStatus(paused.accountRef, paused.groupIdHex),
                    )
                if (CollectionTokenStatus.UNRESOLVED in statuses.values) {
                    // Sending on could duplicate a chunk MDK may already hold, so the operation stays paused.
                    appState.present(R.string.error_try_again)
                    return@launchMutation
                }
                if (paused.withTokenStatuses(statuses, registry.now()).ownedByComposerDraft) {
                    // As on restore: with nothing accepted, the composer's draft still owns the selection, the
                    // caption and its clearing, so sending from here would lose the caption or duplicate the draft.
                    registry.discard(operationId)
                    return@launchMutation
                }
                withContext(Dispatchers.IO) {
                    registry.update(operationId) { current ->
                        skipped = current.unrecoverableItemIds.size
                        current
                            .withTokenStatuses(statuses, registry.now())
                            .resumedWithout(current.unrecoverableItemIds, 1, registry.now())
                    }
                }
            } finally {
                releaseCollectionResume(operationId)
            }
        val resumed = updated ?: return@launchMutation
        val sourceAccount = appState.accounts.firstOrNull { it.label == resumed.accountRef }?.accountIdHex
        val lease =
            acquireStagedSources(context, resumed.items.map { Uri.parse(it.uri) }, sourceAccount).getOrElse {
                // Unleased private sources could be cleaned up mid-read, so the operation waits paused instead.
                registry.update(operationId) { op -> op.copy(paused = true, updatedAtMs = registry.now()) }
                return@launchMutation appState.present(R.string.share_import_storage)
            }
        if (skipped > 0) appState.presentSkippedAttachments(skipped, R.string.attachment_collection_reselect)
        val run = CollectionRun(operationId, resumed.accountRef, resumed.groupIdHex, sessionEpoch, caption = null)
        AttachmentCollectionSender {
            latestCollectionSender(resumed.accountRef, resumed.groupIdHex) ?: original
        }.drive(run, lease)
    }
}

/**
 * Retries the chunk holding later ones: its queued bubble's own Retry when its bytes are still retained, so
 * the same token and any finished uploads are reused, otherwise a fresh preparation from its items' URIs.
 */
internal suspend fun ConversationController.retryCollectionChunk(
    operationId: String,
    chunkId: String,
) {
    val registry = appState.attachmentCollections
    val chunk =
        registry.operations.value[operationId]
            ?.chunks
            ?.firstOrNull { it.id == chunkId }
    val queued = chunk?.clientToken?.let { token -> timeline.firstOrNull { it.id == collectionChunkKey(token) } }
    when {
        chunk?.state != CollectionChunkState.FAILED -> Unit
        // A second tap finds the bubble already retrying and does nothing, so nothing is sent twice.
        queued != null && pendingAttachmentsList(queued.record.messageIdHex).isNotEmpty() ->
            if (queued.status == MessageStatus.Failed) retryFailedSend(queued)
        else -> {
            // No retained bytes are left to retry, so the chunk is prepared again under a fresh token.
            registry.update(operationId) { it.replanned(chunkId, registry.now()) }
            queued?.takeIf { it.status == MessageStatus.Failed }?.let { discardFailedSend(it) }
        }
    }
}

/** Removes a failed chunk's unsent items from the operation so later chunks can go; its bubble is discarded. */
internal suspend fun ConversationController.skipCollectionChunk(
    operationId: String,
    chunkId: String,
) {
    val registry = appState.attachmentCollections
    val chunk =
        registry.operations.value[operationId]
            ?.chunks
            ?.firstOrNull { it.id == chunkId } ?: return
    if (chunk.state.isSettled) return
    chunk.clientToken?.let { token -> timeline.firstOrNull { it.id == collectionChunkKey(token) } }?.let {
        discardFailedSend(it)
    }
    registry.update(operationId) { it.skipItems(chunk.itemIds.toSet(), registry.now()) }
}

/**
 * Cancels everything MDK has not accepted. A queued chunk is withdrawn through its bubble; if MDK already
 * owns it, the cancellation is refused there and the chunk later settles as sent. A paused operation has no
 * runner, so its record is dropped at once.
 */
internal suspend fun ConversationController.cancelCollection(operationId: String) {
    val registry = appState.attachmentCollections
    val before = registry.operations.value[operationId] ?: return
    val cancelled = registry.update(operationId) { it.cancelUnaccepted(registry.now()) }
    if (before.paused || cancelled == null) return registry.discard(operationId)
    // Tokens come from the cancelled snapshot, so one recorded after `before` is withdrawn as well, and a chunk
    // MDK accepted meanwhile is never touched.
    val wasOpen = before.chunks.filter { !it.state.isSettled }.mapTo(hashSetOf()) { it.id }
    val withdrawn =
        cancelled.chunks.filter { chunk ->
            chunk.state != CollectionChunkState.ACCEPTED && (!chunk.state.isSettled || chunk.id in wasOpen)
        }
    withdrawn.mapNotNull { it.clientToken }.forEach { token ->
        val item = timeline.firstOrNull { it.id == collectionChunkKey(token) } ?: return@forEach
        if (item.status == MessageStatus.Failed) {
            discardFailedSend(item)
        } else {
            deleteMessage(item.record, presentFailure = false, optimisticKeyOverride = item.id)
        }
    }
}
