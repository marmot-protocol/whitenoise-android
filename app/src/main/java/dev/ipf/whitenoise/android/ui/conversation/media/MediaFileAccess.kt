package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.util.Log
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import java.io.File

private const val LOG_ID_PREFIX_LENGTH = 8

/** Materializes a reusable artifact for external viewers without duplicating an active transfer. */
internal suspend fun materializeMediaFile(
    context: Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): File? {
    val retained = retainedMediaFileBytes(controller, messageIdHex, attachmentIndex, mine)
    return runCatchingCancellable {
        materializeDocumentAttachment(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
            resolveBytes = {
                controller
                    .requestAttachmentTransfer(
                        messageIdHex = messageIdHex,
                        attachmentIndex = attachmentIndex,
                        reference = reference,
                        retainedPlaintext = retained,
                    ).await()
            },
        )
    }.onFailure {
        logMediaFileDownloadFailure(messageIdHex, attachmentIndex, it)
    }.getOrNull()
}

/** Loads bounded reader content while sharing the controller's durable attachment transfer. */
internal suspend fun loadMediaFileBytes(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): ByteArray? {
    val retained = retainedMediaFileBytes(controller, messageIdHex, attachmentIndex, mine)
    return runCatchingCancellable {
        controller
            .requestAttachmentTransfer(
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                retainedPlaintext = retained,
            ).await()
    }.onFailure {
        logMediaFileDownloadFailure(messageIdHex, attachmentIndex, it)
    }.getOrNull()
}

private fun retainedMediaFileBytes(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    mine: Boolean,
): ByteArray? =
    if (mine) {
        controller
            .pendingAttachmentsList(messageIdHex)
            .getOrNull(attachmentIndex)
            ?.plaintextBytes
    } else {
        null
    }

private fun logMediaFileDownloadFailure(
    messageIdHex: String,
    attachmentIndex: Int,
    error: Throwable,
) {
    Log.w(
        "MediaFileBubble",
        "download failed for msg=${messageIdHex.take(LOG_ID_PREFIX_LENGTH)}#$attachmentIndex",
        error,
    )
}
