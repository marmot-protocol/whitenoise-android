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
                if (MediaReferenceSupport.isVideoMedia(reference)) {
                    val file =
                        materializeVideoAttachment(
                            context = context,
                            controller = controller,
                            messageIdHex = messageIdHex,
                            attachmentIndex = attachmentIndex,
                            reference = reference,
                            mine = mine,
                        )
                    withContext(Dispatchers.IO) {
                        saveVideoToGallery(
                            context = context,
                            source = file,
                            fileName = reference.fileName,
                            mediaType = reference.mediaType,
                        )
                    }
                } else {
                    val bytes =
                        attachmentBytes(
                            controller = controller,
                            messageIdHex = messageIdHex,
                            attachmentIndex = attachmentIndex,
                            reference = reference,
                            mine = mine,
                        )
                    withContext(Dispatchers.IO) {
                        saveAttachmentToMediaStore(
                            context = context,
                            bytes = bytes,
                            fileName = reference.fileName,
                            mediaType = reference.mediaType,
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
