package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.util.Log
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.downloadAttachmentSource
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
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
                if (retained != null) {
                    AttachmentPlaintext.Bytes(
                        controller
                            .requestAttachmentTransfer(
                                messageIdHex = messageIdHex,
                                attachmentIndex = attachmentIndex,
                                reference = reference,
                                retainedPlaintext = retained,
                            ).await(),
                    )
                } else {
                    controller.downloadAttachmentSource(
                        messageIdHex,
                        attachmentIndex,
                        reference,
                        AttachmentDownloadPriority.Interactive,
                    )
                }
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
    awaitDurableWorkFinished: suspend () -> Unit,
    isCachedAfterDurableWork: suspend () -> Boolean,
    onWaitingForDurableAvailability: () -> Unit,
    onTerminalFailure: suspend () -> Unit,
): T? =
    coroutineScope {
        var waitingReported = false
        var artifact: T? = null
        while (artifact == null) {
            val freshAvailability =
                if (durableAvailabilityExpected) {
                    async(start = CoroutineStart.UNDISPATCHED) { awaitNextDurableAvailability() }
                } else {
                    null
                }
            artifact = materialize()
            if (artifact != null) {
                freshAvailability?.cancel()
                break
            }
            if (!waitingReported) {
                onWaitingForDurableAvailability()
                waitingReported = true
            }
            if (freshAvailability == null) {
                onTerminalFailure()
                return@coroutineScope null
            }
            // The availability observer starts before materialization so a fast
            // cache publication cannot be missed. Only start watching terminal
            // work after a failed foreground attempt has enqueued its owner.
            val finished = async(start = CoroutineStart.UNDISPATCHED) { awaitDurableWorkFinished() }
            val workFinished =
                try {
                    select<Boolean> {
                        freshAvailability.onAwait { false }
                        finished.onAwait { true }
                    }
                } finally {
                    freshAvailability.cancel()
                    finished.cancel()
                }
            if (workFinished) {
                // The terminal event can race cache publication. Probe the
                // retained bytes before another materialization, or a failed
                // job would accidentally start a fresh network transfer.
                artifact = if (isCachedAfterDurableWork()) materialize() else null
                if (artifact == null) {
                    onTerminalFailure()
                    return@coroutineScope null
                }
            }
        }
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

/** The sender's own in-memory retry bytes for a pending file, or null so the caller reads MDK's copy instead. */
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
            ?.inMemoryBytes
    } else {
        null
    }

private fun logMediaFileDownloadFailure() {
    Log.w("MediaFileBubble", "attachment_download_failed")
}
