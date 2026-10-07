package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.media.IdentityImageCrop
import dev.ipf.whitenoise.android.media.IdentityImageCropSource
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.state.runCatchingCancellable

/** Current presentation permission only; MDK revalidates authoritative group permission before publishing. */
internal fun canSetViewerGroupPicture(
    isDm: Boolean,
    member: Boolean,
    admin: Boolean,
    editable: Boolean,
    page: MediaViewerPage,
): Boolean = !isDm && member && admin && editable && MediaReferenceSupport.isImageMedia(page.reference)

/**
 * One private viewer-to-crop handoff. Source bytes and a failed prepared draft are transient, never
 * public files or saved-state/protocol caches. Paging cannot retarget the captured attachment.
 */
internal class ViewerGroupPictureSession(
    private val ownerIsCurrent: () -> Boolean,
    private val permitted: () -> Boolean,
) {
    var source by mutableStateOf<IdentityImageCropSource?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var failedDraft by mutableStateOf<ImageUploadDraft?>(null)
        private set
    private var attached = true
    private var operation = 0L

    /** The captured owner remains valid through read, crop, retry and native entry. */
    fun isCurrent(): Boolean = attached && ownerIsCurrent() && permitted()

    /** Reads exactly [page] once; duplicate choices and late completions cannot replace its crop. */
    suspend fun choose(
        page: MediaViewerPage,
        read: suspend (MediaViewerPage) -> IdentityImageCropSource,
        failure: (Throwable) -> Unit,
    ) {
        if (!isCurrent() || busy || source != null || failedDraft != null) return
        val ticket = ++operation
        busy = true
        try {
            runCatchingCancellable { read(page) }.fold(
                onSuccess = { if (owns(ticket)) source = it },
                onFailure = { if (owns(ticket)) failure(it) },
            )
        } finally {
            if (ticket == operation) busy = false
        }
    }

    /** Explicit crop confirmation prepares original pixels; only failed native applications retain a retry draft. */
    suspend fun apply(
        crop: IdentityImageCrop,
        render: suspend (ByteArray, IdentityImageCrop) -> ImageUploadDraft,
        commit: suspend (ImageUploadDraft, Boolean) -> Boolean,
        failure: (Throwable) -> Unit,
    ) {
        val selected = source ?: return
        if (!isCurrent() || busy) return
        val ticket = ++operation
        busy = true
        source = null
        try {
            runCatchingCancellable { render(selected.bytes, crop) }.fold(
                onSuccess = { draft -> if (owns(ticket)) commitDraft(ticket, draft, false, commit) },
                onFailure = { if (owns(ticket)) failure(it) },
            )
        } finally {
            if (ticket == operation) busy = false
        }
    }

    /** Retry must reconcile the actual encrypted group image before any new primary mutation. */
    suspend fun retry(commit: suspend (ImageUploadDraft, Boolean) -> Boolean) {
        val draft = failedDraft ?: return
        if (!isCurrent() || busy) return
        val ticket = ++operation
        busy = true
        try {
            commitDraft(ticket, draft, true, commit)
        } finally {
            if (ticket == operation) busy = false
        }
    }

    /** Retains exact prepared pixels after a failed application; another page cannot supply retry bytes. */
    private suspend fun commitDraft(
        ticket: Long,
        draft: ImageUploadDraft,
        reconcile: Boolean,
        commit: suspend (ImageUploadDraft, Boolean) -> Boolean,
    ) {
        val success = commit(draft, reconcile)
        if (owns(ticket)) failedDraft = draft.takeUnless { success }
    }

    /** Cancellation is side-effect free; it invalidates all callbacks and releases private input. */
    fun cancel() {
        if (busy) return
        operation++
        source = null
        failedDraft = null
    }

    /** Viewer removal fences native entry/feedback and discards transient source and retry state. */
    fun close() {
        attached = false
        operation++
        source = null
        failedDraft = null
        busy = false
    }

    /** A newer operation cannot adopt an older completion, even if it has the same page. */
    private fun owns(ticket: Long): Boolean = ticket == operation && isCurrent()
}
