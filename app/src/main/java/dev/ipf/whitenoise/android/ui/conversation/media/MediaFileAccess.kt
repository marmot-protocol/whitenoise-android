package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.util.Log
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.readWithin
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.downloadAttachmentSource
import dev.ipf.whitenoise.android.state.enqueueInteractiveAttachmentDownload
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
        materializeDocumentFile(context, controller, messageIdHex, attachmentIndex, reference, retained)
    }.onFailure {
        logMediaFileDownloadFailure()
    }.getOrNull()
}

/**
 * Builds a document's reusable file from the sender's in-memory retry bytes when there are some, and
 * otherwise streams it from the native source, so a file of any size is never read into one array.
 * Open and Save share this path; failures propagate to the caller.
 */
internal suspend fun materializeDocumentFile(
    context: Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    retained: ByteArray?,
): File {
    // Durable work backs the foreground stream, so a persisted Open or Save can still complete; enqueued
    // from the caller's thread, as the byte-transfer path does, and coalesced with any earlier request.
    if (retained == null) controller.enqueueInteractiveAttachmentDownload(messageIdHex, attachmentIndex, reference)
    return materializeDocumentAttachmentSource(
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

/**
 * Reads at most [maxBytes] of an attachment for the text reader, streamed from its source so a large file is never
 * copied whole onto the heap. A source declaring or streaming more throws [AttachmentTooLargeToPresentException],
 * which the reader presents as too large to preview rather than as a failed download.
 */
internal suspend fun loadTextReaderBytes(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): ByteArray {
    val source = attachmentSource(controller, messageIdHex, attachmentIndex, reference, mine)
    return source.use { readForTextReader(it) }
}

/**
 * Reads one byte past the reader's preview limit, enough for the decoder to tell an over-limit file apart, and
 * never more, so a large or misreported source is never copied whole onto the heap.
 */
internal suspend fun readForTextReader(source: AttachmentPlaintext): ByteArray = source.readWithin(TEXT_READ_MAX_BYTES)

/** One byte past [TEXT_ATTACHMENT_PREVIEW_MAX_BYTES], the most the text reader ever reads from a source. */
private const val TEXT_READ_MAX_BYTES = TEXT_ATTACHMENT_PREVIEW_MAX_BYTES + 1L

/** The sender's own in-memory retry bytes for a pending file, or null so the caller reads MDK's copy instead. */
internal fun retainedMediaFileBytes(
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
