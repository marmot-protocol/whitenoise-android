package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.SharedPlaintextWrite
import dev.ipf.whitenoise.android.media.sharedPlaintextSuffix
import dev.ipf.whitenoise.android.media.withSharedPlaintextWrite
import dev.ipf.whitenoise.android.state.AttachmentCollectionOperation
import dev.ipf.whitenoise.android.state.CollectionDraftSourceFiles
import dev.ipf.whitenoise.android.state.CollectionItemState
import dev.ipf.whitenoise.android.state.CollectionStoredDraft
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftFileCopy
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.UnverifiedStoredDraftPdf
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentCollections
import dev.ipf.whitenoise.android.state.consumeCollectionDraftFiles
import dev.ipf.whitenoise.android.state.hasVerifiedDraftPdfMetadata
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_DISK_RESERVE_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.prepareStagingDirectory
import dev.ipf.whitenoise.android.ui.conversation.media.usableSpaceFor
import java.io.File
import java.io.IOException

/**
 * Records accepted ownership before removing stored originals. A failed draft read or write leaves the
 * journal recoverable, and repeating a successful removal finds no matching native IDs.
 */
internal suspend fun WhiteNoiseAppState.finishCollectionDraftCleanup(operationId: String) {
    val registry = attachmentCollections
    if (registry.operations.value[operationId]?.needsStoredDraftCleanup != true) return
    val recorded = registry.updateDurably(operationId) { it } ?: return
    val accepted = recorded.pendingDraftFiles
    val consumed =
        runCatchingCancellable {
            marmotIo { consumeCollectionDraftFiles(recorded, accepted) }
        }.getOrDefault(false)
    if (consumed) {
        val ids = accepted.mapTo(HashSet()) { it.attachmentId }
        registry.update(operationId) { operation ->
            val remaining =
                operation.items.map { item ->
                    val source = item.storedDraft
                    if (source != null && source.attachmentId in ids) {
                        item.copy(storedDraft = source.copy(consumed = true))
                    } else {
                        item
                    }
                }
            operation.copy(items = remaining, updatedAtMs = registry.now())
        }
        retainedConversationStates.recordNativeComposerCleanup(recorded.accountRef, recorded.groupIdHex)
    }
}

/** Resolves collection and single-file admission records before opening a sendable native shelf. */
internal suspend fun WhiteNoiseAppState.reconcileDraftSends(
    account: String,
    controller: ConversationController,
) {
    val group = controller.group
    if (!group.disbanded && !group.unrecoverable) {
        restoreCollectionOperations(account, group.groupIdHex, readable = { canReadCollectionUri(appContext, it) })
    }
}

/** Original draft IDs currently covered by a collection or still awaiting a single-send acceptance lookup. */
internal fun WhiteNoiseAppState.collectionOwnedDraftIds(
    account: String,
    group: String,
): Set<String> =
    attachmentCollections.operations.value.values
        .filter { it.accountRef == account && it.groupIdHex == group }
        .flatMap { operation ->
            operation.items.mapNotNull { item ->
                item.storedDraft?.attachmentId.takeUnless {
                    item.state == CollectionItemState.SKIPPED ||
                        item.state == CollectionItemState.CANCELLED
                }
            }
        }.toSet()

/** Copies stored bodies from their selected native revision before the collection accepts the composer shelf. */
internal suspend fun WhiteNoiseAppState.copyStoredCollectionSources(
    context: Context,
    operation: AttachmentCollectionOperation,
    request: CollectionSendRequest,
    permit: SharedPlaintextWrite,
): AttachmentCollectionOperation =
    withSharedPlaintextWrite(permit) {
        ownCollectionDraftSources(context, operation, request) { pending, target ->
            val descriptor = checkNotNull(pending.draftFile)
            marmotIo { DraftFileCopy(this, operation.accountRef, operation.groupIdHex, descriptor).into(target) }
        }
    }

/**
 * Journals will use the operation's private copies, so disposing or restoring the composer cannot remove a later
 * chunk's input. The caller deletes this operation's directory if it never commits the journal. Runs on I/O.
 */
internal suspend fun ownCollectionDraftSources(
    context: Context,
    operation: AttachmentCollectionOperation,
    request: CollectionSendRequest,
    copy: suspend (PendingAttachment, File) -> Boolean,
): AttachmentCollectionOperation {
    val prepared =
        request.imageSlots.map { request.preparedImages[it.id] } +
            request.documentUris.map { request.preparedDocuments[it] }
    val files = CollectionDraftSourceFiles(context.cacheDir)
    val owned =
        operation.items.zip(prepared).mapIndexed { index, (item, body) ->
            val descriptor = body?.draftFile
            if (body == null || descriptor == null) {
                val inline =
                    body?.nativeDraftAttachmentId?.let { id ->
                        CollectionStoredDraft(id, body.byteCount, body.mediaType, fileBacked = false)
                    }
                item.copy(storedDraft = inline)
            } else {
                val target = files.fileFor(context, operation, index, collectionDraftSuffix(body))
                item.copy(
                    uri = copyStoredCollectionItem(context, target, body, copy),
                    storedDraft = CollectionStoredDraft(descriptor.attachmentId, descriptor.byteCount, body.mediaType),
                )
            }
        }
    return operation.copy(items = owned)
}

/** Uses a private buffer on disk, verifies the exact body, and publishes only its completed provider URI. */
private suspend fun copyStoredCollectionItem(
    context: Context,
    target: File,
    pending: PendingAttachment,
    copy: suspend (PendingAttachment, File) -> Boolean,
): String {
    val name = MediaPipeline.safeDisplayName(pending.fileName, "attachment")
    val directory = checkNotNull(target.parentFile)
    if (!prepareStagingDirectory(directory) ||
        usableSpaceFor(directory) < pending.byteCount + FILE_BACKED_DISK_RESERVE_BYTES ||
        !copy(pending, target)
    ) {
        throw IOException("Could not retain stored collection attachment")
    }
    if (!target.hasVerifiedDraftPdfMetadata()) throw UnverifiedStoredDraftPdf()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target, name).toString()
}

/** Gives the private file a filesystem-safe suffix independent of its display name. */
private fun collectionDraftSuffix(pending: PendingAttachment): String {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(pending.mediaType)
    return sharedPlaintextSuffix(extension?.let { "body.$it" } ?: "body.bin")
}
