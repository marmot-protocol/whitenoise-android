package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.ConversationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun saveMessageMediaAttachments(
    context: Context,
    controller: ConversationController,
    messageIdHex: String,
    mediaReferences: List<MediaAttachmentReferenceFfi>,
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
    mediaReferences.forEachIndexed { attachmentIndex, reference ->
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
        totalCount = mediaReferences.size,
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

private suspend fun saveMessageImageAttachment(
    context: MessageAttachmentSaveContext,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
): Boolean {
    val bytes =
        attachmentBytes(
            controller = context.controller,
            messageIdHex = context.messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
            mine = context.mine,
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
                ?.plaintextBytes
        } else {
            null
        }
    val file =
        materializeDocumentAttachment(
            context = context.androidContext,
            messageIdHex = context.messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
            resolveBytes = {
                context.controller
                    .requestAttachmentTransfer(
                        messageIdHex = context.messageIdHex,
                        attachmentIndex = attachmentIndex,
                        reference = reference,
                        retainedPlaintext = retained,
                    ).await()
            },
        )
    return saveDocumentWithFallback(
        context = context.androidContext,
        source = file,
        fileName = reference.fileName,
        mediaType = reference.mediaType,
        fallback = context.documentSaveFallback,
    )
}
