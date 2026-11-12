package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.ui.conversation.media.materializeReceiveContentImageUri
import dev.ipf.whitenoise.android.ui.conversation.media.receiveContentMimeIsImage
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What one dropped batch staged: [admission] counts every image offered, [localUris] the copies that fit and were
 * read.
 */
internal data class ComposerImageDropResult(
    val admission: ComposerAttachmentAdmission<Uri>,
    val localUris: List<Uri>,
    val unreadableCount: Int,
)

/**
 * Imports images dropped on the composer for one account and chat without provider IPC on the main thread (#3715).
 *
 * The receive-content callback only claims the dropped URIs. Here the provider's MIME lookup and the bounded copy into
 * app cache run on [io], while the main thread admits the images under the combined attachment limit and stages the
 * copies. Images admitted by a batch still copying stay reserved, so concurrent drops cannot overfill the shelf.
 *
 * The drop's read grant is requested by Compose for the hosting activity and is never released by it, so it outlives
 * the callback; a grant lost with the activity surfaces as an unreadable image. A result is staged only while
 * the import is still the conversation's owner. A [cancel], an owner change or a failed delivery deletes its copies.
 */
@Stable
internal class ComposerImageDropImport(
    scope: CoroutineScope,
    private val resolveMime: (Uri) -> String,
    private val materialize: (uri: Uri, resolvedMime: String) -> Uri?,
    private val discard: (Uri) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val importScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var activeImports by mutableIntStateOf(0)
    private var reservedSlots = 0

    /** True while any dropped batch is resolving or copying; staged sends wait so a caption cannot leave without it. */
    val isImporting: Boolean get() = activeImports > 0

    /** Resolves MIME types until [capacity] images are found; returns them and how many URIs were never examined. */
    private suspend fun classifyImages(
        uris: List<Uri>,
        capacity: Int,
    ): Pair<List<Pair<Uri, String>>, Int> {
        val found = mutableListOf<Pair<Uri, String>>()
        var examined = 0
        while (examined < uris.size && found.size < capacity) {
            currentCoroutineContext().ensureActive()
            val uri = uris[examined++]
            val mime = resolveMime(uri)
            // The drop's clip declared an image type, so a silent resolver still counts as an image.
            if (receiveContentMimeIsImage(mime, clipDeclaresImage = true)) found += uri to mime
        }
        return found to uris.size - examined
    }

    /**
     * Imports [uris] that the drop callback already claimed. Call on the main thread. [stagedCount] is the shelf's
     * current media and document count, [isCurrent] whether this conversation still owns the composer and [deliver]
     * stages a result; all three run on the main thread.
     */
    fun import(
        uris: List<Uri>,
        maxItems: Int,
        stagedCount: () -> Int,
        isCurrent: () -> Boolean,
        deliver: (ComposerImageDropResult) -> Unit,
    ) {
        if (uris.isEmpty()) return
        activeImports += 1
        // Undispatched, so the finally block runs even when the screen scope is already cancelled.
        importScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var reservation = 0
            val copies = mutableListOf<Uri>()
            var staged = emptySet<Uri>()
            try {
                // Only ask the provider about as many items as could still fit; the rest are reported over the limit.
                val capacity = (maxItems - stagedCount() - reservedSlots).coerceAtLeast(0)
                val (images, unexamined) = withContext(io) { classifyImages(uris, capacity) }
                if ((images.isEmpty() && unexamined == 0) || !isCurrent()) return@launch
                val admitted = admitComposerAttachments(images, stagedCount() + reservedSlots, 0, maxItems)
                val admission = admitted.copy(droppedCount = admitted.droppedCount + unexamined)
                reservation = admission.accepted.size
                reservedSlots += reservation
                withContext(io) {
                    admission.accepted.forEach { (uri, mime) ->
                        ensureActive()
                        materialize(uri, mime)?.let { copy -> synchronized(copies) { copies += copy } }
                    }
                }
                if (!isCurrent()) return@launch
                reservedSlots -= reservation
                reservation = 0
                // Another path may have filled the shelf while this batch copied; never exceed the limit.
                val readable = synchronized(copies) { copies.toList() }
                val fit = readable.take((maxItems - stagedCount() - reservedSlots).coerceAtLeast(0))
                val lateDropped = readable.size - fit.size
                val result =
                    ComposerImageDropResult(
                        admission =
                            ComposerAttachmentAdmission(
                                accepted = admission.accepted.dropLast(lateDropped).map { it.first },
                                droppedCount = admission.droppedCount + lateDropped,
                            ),
                        localUris = fit,
                        unreadableCount = admission.accepted.size - readable.size,
                    )
                deliver(result)
                staged = fit.toSet()
            } finally {
                reservedSlots -= reservation
                // The import stays active until its unstaged copies are gone, so nothing observes it half-cleaned.
                try {
                    val undelivered = synchronized(copies) { copies.filterNot { it in staged } }
                    if (undelivered.isNotEmpty()) {
                        withContext(NonCancellable + io) { undelivered.forEach { runCatching { discard(it) } } }
                    }
                } finally {
                    activeImports -= 1
                }
            }
        }
    }

    /** Cancels every import; their copies are deleted and nothing they read is staged. */
    fun cancel() {
        importScope.cancel()
    }
}

/** One drop importer per account and chat, cancelled with the screen scope, on dispose and when either key changes. */
@Composable
internal fun rememberComposerImageDropImport(
    scope: CoroutineScope,
    accountRef: String?,
    chatId: String,
): ComposerImageDropImport {
    val context = LocalContext.current.applicationContext
    val dropImport =
        remember(scope, accountRef, chatId) {
            ComposerImageDropImport(
                scope = scope,
                resolveMime = { uri -> safeGetType(context.contentResolver, uri) },
                materialize = { uri, mime -> materializeReceiveContentImageUri(context, uri, mime) },
                discard = { copy -> context.contentResolver.delete(copy, null, null) },
            )
        }
    DisposableEffect(dropImport) { onDispose { dropImport.cancel() } }
    return dropImport
}
