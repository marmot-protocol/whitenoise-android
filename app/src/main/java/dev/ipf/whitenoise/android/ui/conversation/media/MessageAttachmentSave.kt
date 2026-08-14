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
): MessageAttachmentSaveSummary {
    var savedCount = 0
    var firstFailure: Throwable? = null
    mediaReferences.forEachIndexed { attachmentIndex, reference ->
        val result =
            runCatching<Boolean> {
                val resolvedReference =
                    if (mine) {
                        reference
                    } else {
                        controller.authoritativeAttachmentReference(messageIdHex, attachmentIndex, reference)
                    }
                if (MediaReferenceSupport.isVideoMedia(resolvedReference)) {
                    val file =
                        materializeVideoAttachment(
                            context = context,
                            controller = controller,
                            messageIdHex = messageIdHex,
                            attachmentIndex = attachmentIndex,
                            reference = resolvedReference,
                            mine = mine,
                        )
                    withContext(Dispatchers.IO) {
                        saveVideoToGallery(
                            context = context,
                            source = file,
                            fileName = resolvedReference.fileName,
                            mediaType = resolvedReference.mediaType,
                        )
                    }
                } else if (MediaReferenceSupport.isImageMedia(resolvedReference)) {
                    val bytes =
                        attachmentBytes(
                            controller = controller,
                            messageIdHex = messageIdHex,
                            attachmentIndex = attachmentIndex,
                            reference = resolvedReference,
                            mine = mine,
                        )
                    withContext(Dispatchers.IO) {
                        saveAttachmentToMediaStore(
                            context = context,
                            bytes = bytes,
                            fileName = resolvedReference.fileName,
                            mediaType = resolvedReference.mediaType,
                        )
                    }
                } else {
                    val retained =
                        if (mine) {
                            controller
                                .pendingAttachmentsList(messageIdHex)
                                .getOrNull(attachmentIndex)
                                ?.plaintextBytes
                        } else {
                            null
                        }
                    val file =
                        materializeDocumentAttachment(
                            context = context,
                            messageIdHex = messageIdHex,
                            attachmentIndex = attachmentIndex,
                            reference = resolvedReference,
                            resolveBytes = {
                                controller
                                    .requestAttachmentTransfer(
                                        messageIdHex = messageIdHex,
                                        attachmentIndex = attachmentIndex,
                                        reference = resolvedReference,
                                        retainedPlaintext = retained,
                                    ).await()
                            },
                        )
                    withContext(Dispatchers.IO) {
                        saveDocumentToDownloads(
                            context = context,
                            source = file,
                            fileName = resolvedReference.fileName,
                            mediaType = resolvedReference.mediaType,
                        )
                    }
                }
            }.mapCatching { saved ->
                check(saved) { "MediaStore save returned false" }
                true
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
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
