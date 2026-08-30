package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
