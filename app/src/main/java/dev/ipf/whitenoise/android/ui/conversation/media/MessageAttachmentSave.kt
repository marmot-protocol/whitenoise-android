package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageAttachmentSaveOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MessageAttachmentSaveSummary(
    val savedCount: Int,
    val totalCount: Int,
) {
    val outcome: MessageAttachmentSaveOutcome
        get() = MessageAttachmentSaveOutcome.from(savedCount, totalCount)

    companion object {
        val Empty = MessageAttachmentSaveSummary(savedCount = 0, totalCount = 0)
    }
}

internal fun aggregateMessageAttachmentSaveSummaries(summaries: Iterable<MessageAttachmentSaveSummary>): MessageAttachmentSaveSummary =
    summaries.fold(MessageAttachmentSaveSummary.Empty) { aggregate, summary ->
        MessageAttachmentSaveSummary(
            savedCount = aggregate.savedCount + summary.savedCount,
            totalCount = aggregate.totalCount + summary.totalCount,
        )
    }

internal suspend fun saveMessageMediaAttachments(
    context: Context,
    controller: ConversationController,
    messageIdHex: String,
    mediaReferences: List<MediaAttachmentReferenceFfi>,
    mine: Boolean,
): Int {
    var savedCount = 0
    mediaReferences.forEachIndexed { attachmentIndex, reference ->
        val saved =
            runCatching {
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
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
            }.getOrDefault(false)
        if (saved) savedCount += 1
    }
    return savedCount
}

internal fun WhiteNoiseAppState.presentAttachmentSaveOutcome(
    context: Context,
    savedCount: Int,
    totalCount: Int,
) = presentAttachmentSaveOutcome(
    context = context,
    summary = MessageAttachmentSaveSummary(savedCount, totalCount),
)

internal fun WhiteNoiseAppState.presentAttachmentSaveOutcome(
    context: Context,
    summary: MessageAttachmentSaveSummary,
) {
    when (summary.outcome) {
        MessageAttachmentSaveOutcome.Complete -> present(R.string.shared_media_saved)
        MessageAttachmentSaveOutcome.Partial ->
            present(
                title = context.getString(R.string.shared_media_saved),
                detail =
                    context.getString(
                        R.string.conversation_search_match_count,
                        summary.savedCount,
                        summary.totalCount,
                    ),
            )
        MessageAttachmentSaveOutcome.Failed -> present(R.string.shared_media_save_failed, copyable = true)
    }
}
