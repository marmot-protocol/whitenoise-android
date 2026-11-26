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
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftFileCopy
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.UnverifiedStoredDraftPdf
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentCollections
import dev.ipf.whitenoise.android.state.captureDraftSource
import dev.ipf.whitenoise.android.state.consumeCollectionDraftFiles
import dev.ipf.whitenoise.android.state.hasVerifiedDraftPdfMetadata
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.state.sendOwnedDraftIds
import dev.ipf.whitenoise.android.ui.conversation.media.FILE_BACKED_DISK_RESERVE_BYTES
import dev.ipf.whitenoise.android.ui.conversation.media.prepareStagingDirectory
import dev.ipf.whitenoise.android.ui.conversation.media.usableSpaceFor
import java.io.File
import java.io.IOException

/**
 * Records accepted ownership before removing stored originals and the accepted caption. A failed draft read or write
 * leaves the journal recoverable, and repeating a successful removal finds no matching native IDs or draft save.
 * A caption a restored record clears, after a restart, is reloaded out of an editor that still shows it.
 */
internal suspend fun WhiteNoiseAppState.finishCollectionDraftCleanup(operationId: String) {
    val registry = attachmentCollections
    if (registry.operations.value[operationId]?.needsStoredDraftCleanup != true) return
    val recorded = registry.updateDurably(operationId) { it } ?: return
    val accepted = recorded.pendingDraftFiles
    val caption = recorded.pendingSentCaption
    var captionCleared = false
    val consumed =
        runCatchingCancellable {
            marmotIo { consumeCollectionDraftFiles(recorded, accepted, caption) { captionCleared = true } }
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
            operation.copy(
                items = remaining,
                sentCaption = operation.sentCaption.takeUnless { it == caption },
                updatedAtMs = registry.now(),
            )
        }
        retainedConversationStates.recordNativeComposerCleanup(recorded.accountRef, recorded.groupIdHex)
        // A live send clears its own editor on acceptance. A record restored after a restart has no such owner, and
        // the reopened editor may already show the caption this just removed.
        if (captionCleared && recorded.paused) loadDraft(recorded.accountRef, recorded.groupIdHex)
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

/**
 * Original draft IDs the composer shelf must not offer: those covered by a collection or awaiting a single-send
 * acceptance lookup, and those a send of this chat still owns in this process.
 */
internal fun WhiteNoiseAppState.collectionOwnedDraftIds(
    account: String,
    group: String,
): Set<String> =
    attachmentCollections.operations.value.values
        .filter { it.accountRef == account && it.groupIdHex == group }
        .flatMapTo(HashSet()) { operation ->
            operation.items.mapNotNull { item ->
                item.storedDraft?.attachmentId.takeUnless {
                    item.state == CollectionItemState.SKIPPED ||
                        item.state == CollectionItemState.CANCELLED
                }
            }
        } + sendOwnedDraftIds(account, group)

/**
 * Lets the composer read its native draft again once [operation] stops hiding originals from the shelf: a discarded or
 * cancelled collection keeps its unsent originals in the draft, and nothing else tells a mounted shelf to show them.
 */
internal fun WhiteNoiseAppState.showReleasedCollectionOriginals(operation: AttachmentCollectionOperation) {
    if (operation.items.any { it.storedDraft?.consumed == false }) {
        retainedConversationStates.recordNativeComposerCleanup(operation.accountRef, operation.groupIdHex)
    }
}

/** Discards [operationId] at the user's request and returns the originals it was hiding to the shelf. */
internal fun WhiteNoiseAppState.discardCollection(operationId: String) {
    val operation = attachmentCollections.operations.value[operationId] ?: return
    attachmentCollections.discard(operationId)
    showReleasedCollectionOriginals(operation)
}

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
                item.copy(storedDraft = body?.captureDraftSource())
            } else {
                val target = files.fileFor(context, operation, index, collectionDraftSuffix(body))
                item.copy(
                    uri = copyStoredCollectionItem(context, target, body, copy),
                    storedDraft = body.captureDraftSource(),
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
