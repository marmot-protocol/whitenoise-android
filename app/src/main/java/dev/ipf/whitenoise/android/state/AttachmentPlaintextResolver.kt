package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Checks L1, hydrated L2, then L1 again to cover a concurrent cache publication. */
internal suspend fun resolveAttachmentCacheAvailability(
    cacheKey: String,
    memoryContains: (String) -> Boolean,
    diskContains: (String) -> Boolean,
): Boolean =
    withContext(Dispatchers.Main.immediate) { memoryContains(cacheKey) } ||
        withContext(Dispatchers.IO) { diskContains(cacheKey) } ||
        withContext(Dispatchers.Main.immediate) { memoryContains(cacheKey) }

/** Returns bounded memory or an owner-private file lease that the caller must close. */
internal suspend fun WhiteNoiseAppState.downloadAttachmentPlaintextSource(
    request: AttachmentTransferRequest,
    reference: MediaAttachmentReferenceFfi,
    priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
    persistInteractiveIntent: Boolean = true,
    onCacheMiss: (suspend () -> ByteArray)? = null,
): AttachmentPlaintext {
    val cacheKey = request.run { mediaCacheKey(accountRef, groupIdHex, messageIdHex, attachmentIndex) }
    return resolveAttachmentPlaintext(
        loadMemory = { withContext(Dispatchers.Main.immediate) { cachedMediaPlaintext(cacheKey) } },
        loadDisk = { cancellationCheck, onAcquired ->
            withContext(Dispatchers.IO) {
                val loaded =
                    diskMediaCache.getIfSmall(cacheKey)?.let(AttachmentPlaintext::Bytes)
                        ?: diskMediaCache
                            .materialize(cacheKey, cancellationCheck)
                            ?.let(AttachmentPlaintext::Lease)
                onAcquired(loaded)
                loaded
            }
        },
        cacheMemory = { bytes ->
            withContext(Dispatchers.Main.immediate) { cacheMediaPlaintext(cacheKey, bytes) }
        },
        clearInteractiveIntent = {
            if (priority == AttachmentDownloadPriority.Interactive && persistInteractiveIntent) {
                clearInteractiveAttachmentDownloadIntent(request)
            }
        },
        loadMiss = {
            onCacheMiss?.invoke()
                ?: downloadAttachmentPlaintext(request, reference, priority, persistInteractiveIntent)
        },
    )
}

/**
 * Chooses bounded in-memory or private-file attachment plaintext and transfers
 * lease ownership to the caller only after all post-load bookkeeping succeeds.
 * `loadDisk` must invoke its acquisition callback before crossing back from the
 * dispatcher where the source was acquired, so cancellation can close it.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun resolveAttachmentPlaintext(
    loadMemory: suspend () -> ByteArray?,
    loadDisk: suspend (
        cancellationCheck: () -> Unit,
        onAcquired: (AttachmentPlaintext?) -> Unit,
    ) -> AttachmentPlaintext?,
    cacheMemory: suspend (ByteArray) -> Unit,
    clearInteractiveIntent: suspend () -> Unit,
    loadMiss: suspend () -> ByteArray,
): AttachmentPlaintext {
    val memory = loadMemory()
    val callerContext = currentCoroutineContext()
    var source: AttachmentPlaintext? = null
    var pendingSource: AttachmentPlaintext? = null
    try {
        source =
            memory?.let(AttachmentPlaintext::Bytes)
                ?: loadDisk(
                    { callerContext.ensureActive() },
                    { pendingSource = it },
                )
        pendingSource = null
        source?.let { resolved ->
            if (resolved is AttachmentPlaintext.Bytes && memory == null) {
                cacheMemory(resolved.bytes)
            }
            clearInteractiveIntent()
            return resolved
        }
    } catch (cancellation: CancellationException) {
        (source ?: pendingSource)?.close()
        throw cancellation
    } catch (error: Throwable) {
        (source ?: pendingSource)?.close()
        throw error
    }
    return AttachmentPlaintext.Bytes(loadMiss())
}
