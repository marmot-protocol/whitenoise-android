package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MediaFileUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaFileUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.whitenoise.android.ui.conversation.media.FileUploadSources
import dev.ipf.whitenoise.android.ui.conversation.media.stageFileUploadSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Test seam for file-backed composer uploads: (account, group, request, registerCancel) -> upload result.
 * A fake registers a cancel callback the way the native transfer control would, and clears it with null.
 */
internal typealias MediaFileUploader =
    suspend (String, String, MediaFileUploadRequestFfi, ((() -> Unit)?) -> Unit) -> MediaUploadResultFfi

/** Whether a retained send must go through the native file upload because one of its items lives in a file. */
internal val RetainedMediaUpload.isFileBacked: Boolean
    get() = attachments.any { it.sourceFile != null }

/**
 * Builds the native request for one file-backed send and keeps every source path pinned until [upload]
 * returns. In-memory album items become private snapshots for this call only and are deleted afterwards;
 * the send's own staged files stay with its retained upload so a retry reuses them. Ownership of the staged
 * batch is taken inside the staging block itself: `withContext` discards its result when the caller is
 * cancelled on the way back, so returning the batch would leak its snapshots and pins.
 */
internal suspend fun <T> withFileUploadRequest(
    attachments: List<PendingAttachment>,
    caption: String?,
    stagingDirectory: File,
    maxCiphertextBytes: Long,
    upload: suspend (MediaFileUploadRequestFfi) -> T,
): T {
    val owned = AtomicReference<FileUploadSources?>(null)
    try {
        withContext(NonCancellable + Dispatchers.IO) {
            owned.set(stageFileUploadSources(attachments, stagingDirectory, maxCiphertextBytes))
        }
        currentCoroutineContext().ensureActive()
        val sources = checkNotNull(owned.get())
        val request =
            MediaFileUploadRequestFfi(
                attachments =
                    sources.inputs.map { input ->
                        MediaFileUploadAttachmentRequestFfi(
                            sourcePath = input.sourcePath,
                            expectedSize = input.byteCount.toULong(),
                            fileName = input.fileName,
                            mediaType = input.mediaType,
                            dim = input.dim,
                            thumbhash = input.thumbhash,
                        )
                    },
                caption = caption,
                send = false,
                blossomServer = null,
            )
        return upload(request)
    } finally {
        // A failed unlink must not replace the upload's own result or error; the startup sweep retries it.
        owned.getAndSet(null)?.let { sources ->
            withContext(NonCancellable + Dispatchers.IO) { runCatching { sources.close() } }
        }
    }
}

// How often a running native transfer's byte counter is read for display.
private const val TRANSFER_PROGRESS_POLL_MILLIS = 200L

/**
 * Runs [upload] with a fresh native transfer control. [register] exposes its cancel to the user's
 * Cancel action while the call runs, and may apply a Cancel recorded earlier at once; it is cleared
 * before the control is released. Cancelling the calling coroutine (an account switch) also cancels
 * the control, so MDK stops at its next check unless admission has already started, which is not
 * interruptible. When [onProgress] is given, it hears the control's byte counter every 200 ms while
 * the call runs and once more as it ends, and never after the control is released.
 */
internal suspend fun <T> withNativeTransferControl(
    register: ((() -> Unit)?) -> Unit,
    onProgress: ((Long) -> Unit)? = null,
    newControl: () -> MediaFileTransferControlFfi = ::MediaFileTransferControlFfi,
    upload: suspend (MediaFileTransferControlFfi) -> T,
): T {
    val control = newControl()
    try {
        // Registering can cancel at once (a Cancel recorded earlier), so it sits inside the release scope.
        register(control::cancel)
        if (onProgress == null) return upload(control)
        return coroutineScope {
            // Reading the counter is a binding call, so it stays off Main. The scope waits for the reader
            // to stop before the control is released below.
            // ATOMIC: a reader cancelled before it is dispatched still runs, so its final read is never skipped.
            val reader =
                launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
                    reportTransferProgress(control, onProgress)
                }
            try {
                upload(control)
            } finally {
                reader.cancel()
            }
        }
    } catch (cancelled: CancellationException) {
        control.cancel()
        throw cancelled
    } finally {
        register(null)
        control.close()
    }
}

/**
 * Reports [control]'s byte counter to [onProgress] until cancelled, then once more with its final value.
 * Progress is display only, so a failed read stops the reports and never fails the transfer itself.
 */
private suspend fun reportTransferProgress(
    control: MediaFileTransferControlFfi,
    onProgress: (Long) -> Unit,
) {
    var readable = true
    val report = {
        // A read that fails once is not retried, so a broken counter cannot flood the log or the screen.
        readable = readable && runCatching { onProgress(control.processedBytes().toLong()) }.isSuccess
    }
    try {
        while (readable) {
            report()
            delay(TRANSFER_PROGRESS_POLL_MILLIS)
        }
    } finally {
        if (readable) report()
    }
}
