package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MediaFileUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaFileUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.whitenoise.android.ui.conversation.media.stageFileUploadSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

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
 * the send's own staged files stay with its retained upload so a retry reuses them. Staging cannot be
 * abandoned half-way: a cancellation that arrives during it is observed only once its pins are owned here.
 */
internal suspend fun <T> withFileUploadRequest(
    attachments: List<PendingAttachment>,
    caption: String?,
    stagingDirectory: File,
    maxCiphertextBytes: Long,
    upload: suspend (MediaFileUploadRequestFfi) -> T,
): T {
    val sources =
        withContext(NonCancellable + Dispatchers.IO) {
            stageFileUploadSources(attachments, stagingDirectory, maxCiphertextBytes)
        }
    try {
        currentCoroutineContext().ensureActive()
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
        withContext(NonCancellable + Dispatchers.IO) { runCatching { sources.close() } }
    }
}

/**
 * Runs [upload] with a fresh native transfer control. [register] exposes its cancel to the user's
 * Cancel action while the call runs, and may apply a Cancel recorded earlier at once; it is cleared
 * before the control is released. Cancelling the calling coroutine (an account switch) also cancels
 * the control, so MDK stops at its next check unless admission has already started, which is not
 * interruptible.
 */
internal suspend fun <T> withNativeTransferControl(
    register: ((() -> Unit)?) -> Unit,
    newControl: () -> MediaFileTransferControlFfi = ::MediaFileTransferControlFfi,
    upload: suspend (MediaFileTransferControlFfi) -> T,
): T {
    val control = newControl()
    try {
        // Registering can cancel at once (a Cancel recorded earlier), so it sits inside the release scope.
        register(control::cancel)
        return upload(control)
    } catch (cancelled: CancellationException) {
        control.cancel()
        throw cancelled
    } finally {
        register(null)
        control.close()
    }
}
