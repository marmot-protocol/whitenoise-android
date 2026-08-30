package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.util.Log
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File

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
        materializeDocumentAttachmentSource(
            context = context,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            reference = reference,
            resolveSource = {
                retained?.let(AttachmentPlaintext::Bytes)
                    ?: controller.downloadAttachmentSource(
                        messageIdHex,
                        attachmentIndex,
                        reference,
                        AttachmentDownloadPriority.Interactive,
                    )
            },
        )
    }.onFailure {
        logMediaFileDownloadFailure()
    }.getOrNull()
}

/**
 * Keeps a persisted viewer intent alive when the foreground attempt fails but
 * durable work can still publish the same attachment into the encrypted cache.
 */
internal suspend fun <T> materializePersistedAttachmentOpen(
    materialize: suspend () -> T?,
    durableAvailabilityExpected: Boolean,
    awaitNextDurableAvailability: suspend () -> Unit,
    onWaitingForDurableAvailability: () -> Unit,
    onTerminalFailure: () -> Unit,
): T? =
    coroutineScope {
        var waitingReported = false
        var artifact: T?
        do {
            val freshAvailability =
                if (durableAvailabilityExpected) {
                    async(start = CoroutineStart.UNDISPATCHED) { awaitNextDurableAvailability() }
                } else {
                    null
                }
            artifact = materialize()
            if (artifact != null) {
                freshAvailability?.cancel()
            } else {
                if (!waitingReported) {
                    onWaitingForDurableAvailability()
                    waitingReported = true
                }
                if (freshAvailability == null) {
                    onTerminalFailure()
                    return@coroutineScope null
                }
                freshAvailability.await()
            }
        } while (artifact == null)
        artifact
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
        logMediaFileDownloadFailure()
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

private fun logMediaFileDownloadFailure() {
    Log.w("MediaFileBubble", "attachment_download_failed")
}
