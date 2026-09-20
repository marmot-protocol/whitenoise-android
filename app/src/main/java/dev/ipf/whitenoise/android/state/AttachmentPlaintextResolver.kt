package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** Checks L1, hydrated L2, then L1 again to cover a concurrent cache publication. */
internal suspend fun resolveAttachmentCacheAvailability(
    cacheKey: String,
    memoryContains: (String) -> Boolean,
    diskContains: (String) -> Boolean,
): Boolean =
    withContext(Dispatchers.Main.immediate) { memoryContains(cacheKey) } ||
        withContext(Dispatchers.IO) { diskContains(cacheKey) } ||
        withContext(Dispatchers.Main.immediate) { memoryContains(cacheKey) }

/** Resolves every byte-returning consumer through the same native-aware retained-media owner. */
internal suspend fun WhiteNoiseAppState.downloadAttachmentPlaintext(
    request: AttachmentTransferRequest,
    reference: MediaAttachmentReferenceFfi,
    priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Interactive,
    persistInteractiveIntent: Boolean = true,
): ByteArray =
    downloadAttachmentPlaintextSource(
        request = request,
        reference = reference,
        priority = priority,
        persistInteractiveIntent = persistInteractiveIntent,
    ).use { source ->
        withContext(Dispatchers.IO) { source.toByteArray() }
    }

/** Returns bounded memory or an owner-private file lease that the caller must close. */
@Suppress("ReturnCount", "TooGenericExceptionCaught")
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
            loadAttachmentDiskPlaintext(cacheKey, cancellationCheck, onAcquired)
        },
        cacheMemory = { bytes ->
            withContext(Dispatchers.Main.immediate) { cacheMediaPlaintext(cacheKey, bytes) }
        },
        clearInteractiveIntent = {
            clearInteractiveAttachmentIntentAfterSuccess(request, priority, persistInteractiveIntent)
        },
        loadMiss = {
            acquireAttachmentPlaintextSource(
                cacheKey = cacheKey,
                request = request,
                reference = reference,
                priority = priority,
                persistInteractiveIntent = persistInteractiveIntent,
                onCacheMiss = onCacheMiss,
            )
        },
    )
}

/** Loads one Android-retained cache entry while exposing lease acquisition to cancellation cleanup. */
private suspend fun WhiteNoiseAppState.loadAttachmentDiskPlaintext(
    cacheKey: String,
    cancellationCheck: () -> Unit,
    onAcquired: (AttachmentPlaintext?) -> Unit,
): AttachmentPlaintext? =
    withContext(Dispatchers.IO) {
        val loaded =
            diskMediaCache.getIfSmall(cacheKey)?.let(AttachmentPlaintext::Bytes)
                ?: diskMediaCache
                    .materialize(cacheKey, cancellationCheck)
                    ?.let(AttachmentPlaintext::Lease)
        onAcquired(loaded)
        loaded
    }

/** Clears durable interactive demand only for a successfully fulfilled explicit request. */
private fun WhiteNoiseAppState.clearInteractiveAttachmentIntentAfterSuccess(
    request: AttachmentTransferRequest,
    priority: AttachmentDownloadPriority,
    persistInteractiveIntent: Boolean,
) {
    if (priority == AttachmentDownloadPriority.Interactive && persistInteractiveIntent) {
        clearInteractiveAttachmentDownloadIntent(request)
    }
}

/** Shares network-path selection, then gives each native consumer an independent plaintext lease. */
private suspend fun WhiteNoiseAppState.acquireAttachmentPlaintextSource(
    cacheKey: String,
    request: AttachmentTransferRequest,
    reference: MediaAttachmentReferenceFfi,
    priority: AttachmentDownloadPriority,
    persistInteractiveIntent: Boolean,
    onCacheMiss: (suspend () -> ByteArray)?,
): AttachmentPlaintext {
    val resolved =
        memoizedAttachmentAcquisition(cacheKey, priority) {
            val qualifiedRequest =
                resolveNativeAttachmentTarget(request)?.let { target ->
                    request.copy(sourceMessageIdHex = target.sourceMessageIdHex)
                }
            if (qualifiedRequest != null && hasNativeAttachment(qualifiedRequest)) {
                AttachmentAcquisitionOutcome.NativeRetained(qualifiedRequest)
            } else {
                val native = qualifiedRequest?.let { acquireNativeAttachment(it, priority) }
                if (native != null) {
                    native.close()
                    AttachmentAcquisitionOutcome.NativeRetained(checkNotNull(qualifiedRequest))
                } else {
                    AttachmentAcquisitionOutcome.LegacyBytes(
                        onCacheMiss?.invoke()
                            ?: downloadLegacyAttachmentPlaintext(
                                request,
                                reference,
                                priority,
                                persistInteractiveIntent,
                            ),
                    )
                }
            }
        }.await()
    return materializeAttachmentAcquisition(
        outcome = resolved,
        openNative = ::openNativeAttachment,
        afterSuccess = {
            clearInteractiveAttachmentIntentAfterSuccess(request, priority, persistInteractiveIntent)
        },
    )
}

/** Clears durable demand only after the selected retained source is open and caller-owned. */
internal suspend fun materializeAttachmentAcquisition(
    outcome: AttachmentAcquisitionOutcome,
    openNative: suspend (AttachmentTransferRequest) -> AttachmentPlaintext?,
    afterSuccess: suspend () -> Unit,
): AttachmentPlaintext {
    val source =
        when (outcome) {
            is AttachmentAcquisitionOutcome.LegacyBytes -> AttachmentPlaintext.Bytes(outcome.bytes)
            is AttachmentAcquisitionOutcome.NativeRetained ->
                openNative(outcome.request)
                    ?: throw IOException("native attachment acquisition completed without retained bytes")
        }
    var completed = false
    try {
        afterSuccess()
        completed = true
        return source
    } finally {
        if (!completed) source.close()
    }
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
    loadMiss: suspend () -> AttachmentPlaintext,
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
    return loadMiss()
}
