package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.core.IndexedAttachment
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.ATTACHMENT_EXPLICIT_READ_MAX_BYTES
import dev.ipf.whitenoise.android.state.ConversationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saves every accepted attachment under its protocol index; one failure does not stop the rest. */
internal suspend fun saveMessageMediaAttachments(
    context: Context,
    controller: ConversationController,
    messageIdHex: String,
    attachments: List<IndexedAttachment>,
    mine: Boolean,
    documentSaveFallback: DocumentSaveFallback? = null,
): MessageAttachmentSaveSummary {
    var savedCount = 0
    var firstFailure: Throwable? = null
    val saveContext =
        MessageAttachmentSaveContext(
            androidContext = context,
            controller = controller,
            messageIdHex = messageIdHex,
            mine = mine,
            documentSaveFallback = documentSaveFallback,
        )
    attachments.forEach { (attachmentIndex, reference) ->
        val result =
            runCatching<Boolean> {
                saveMessageMediaAttachment(saveContext, attachmentIndex, reference)
            }.mapCatching { saved ->
                check(saved) { "MediaStore save returned false" }
                true
            }.onFailure {
                it.rethrowParentCancellation()
            }
        val saved = result.getOrDefault(false)
        if (saved) savedCount += 1
        if (!saved && firstFailure == null) firstFailure = result.exceptionOrNull()
    }
    return MessageAttachmentSaveSummary(
        savedCount = savedCount,
        totalCount = attachments.size,
        firstFailure = firstFailure,
    )
}

internal fun Throwable.rethrowParentCancellation() {
    when (this) {
        is DocumentDestinationCancelledException -> Unit
        is kotlinx.coroutines.CancellationException -> throw this
    }
}

private data class MessageAttachmentSaveContext(
    val androidContext: Context,
    val controller: ConversationController,
    val messageIdHex: String,
    val mine: Boolean,
    val documentSaveFallback: DocumentSaveFallback?,
)

private suspend fun saveMessageMediaAttachment(
    context: MessageAttachmentSaveContext,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): Boolean {
    val resolvedReference =
        if (context.mine) {
            reference
        } else {
            context.controller.authoritativeAttachmentReference(
                context.messageIdHex,
                attachmentIndex,
                reference,
            )
        }
    return when {
        MediaReferenceSupport.isVideoMedia(resolvedReference) ->
            saveMessageVideoAttachment(context, attachmentIndex, resolvedReference)
        MediaReferenceSupport.isImageMedia(resolvedReference) ->
            saveMessageImageAttachment(context, attachmentIndex, resolvedReference)
        else -> saveMessageDocumentAttachment(context, attachmentIndex, resolvedReference)
    }
}

/** Saves one video to the gallery from a materialized file, never a whole-array read. */
private suspend fun saveMessageVideoAttachment(
    context: MessageAttachmentSaveContext,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): Boolean {
    val file =
        materializeVideoAttachment(
            context = context.androidContext,
            controller = context.controller,
            messageIdHex = context.messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
            mine = context.mine,
        )
    return withContext(Dispatchers.IO) {
        saveVideoToGallery(
            context = context.androidContext,
            source = file,
            fileName = reference.fileName,
            mediaType = reference.mediaType,
        )
    }
}

/** Saves one image's whole verified bytes to the gallery; explicit saves are not bound by the preview budget. */
private suspend fun saveMessageImageAttachment(
    context: MessageAttachmentSaveContext,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): Boolean {
    // An explicit Save hands the whole verified image to MediaStore, so it is not bounded by the preview budget.
    val bytes =
        attachmentBytes(
            controller = context.controller,
            messageIdHex = context.messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
            mine = context.mine,
            maxBytes = ATTACHMENT_EXPLICIT_READ_MAX_BYTES,
        )
    return withContext(Dispatchers.IO) {
        saveAttachmentToMediaStore(
            context = context.androidContext,
            bytes = bytes,
            fileName = reference.fileName,
            mediaType = reference.mediaType,
        )
    }
}

/**
 * Saves one document to Downloads from a materialized file, preferring the sender's in-memory retry bytes
 * and otherwise streaming the native source, so a document larger than the preview budget still saves.
 */
private suspend fun saveMessageDocumentAttachment(
    context: MessageAttachmentSaveContext,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): Boolean {
    val retained =
        if (context.mine) {
            context.controller
                .pendingAttachmentsList(context.messageIdHex)
                .getOrNull(attachmentIndex)
                ?.inMemoryBytes
        } else {
            null
        }
    val file =
        materializeDocumentFile(
            context = context.androidContext,
            controller = context.controller,
            messageIdHex = context.messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
            retained = retained,
        )
    return saveDocumentWithFallback(
        context = context.androidContext,
        source = file,
        fileName = reference.fileName,
        mediaType = reference.mediaType,
        fallback = context.documentSaveFallback,
    )
}
