package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentControlFfi
import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.AttachmentTransferSubscription
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

private const val NATIVE_ATTACHMENT_IN_MEMORY_LIMIT_BYTES = 32L * 1024L * 1024L

private val NATIVE_TRANSFER_TERMINAL_FAILURES =
    setOf(
        AttachmentTransferStateFfi.UNAVAILABLE,
        AttachmentTransferStateFfi.FAILED,
        AttachmentTransferStateFfi.CANCELLED,
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.POLICY_BLOCKED,
    )

/** True only for explicit user demand with the complete native attachment identity. */
internal fun shouldUseNativeExplicitDemand(
    priority: AttachmentDownloadPriority,
    target: NativeAttachmentTarget?,
): Boolean = priority == AttachmentDownloadPriority.Interactive && target != null

/** Converts MarmotKit's detailed state into the existing presentation vocabulary. */
internal fun AttachmentTransferStatusFfi.toPresentationState(): AttachmentTransferState =
    when (state) {
        AttachmentTransferStateFfi.UNAVAILABLE,
        AttachmentTransferStateFfi.NOT_REQUESTED,
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.POLICY_BLOCKED,
        -> AttachmentTransferState.Remote
        AttachmentTransferStateFfi.QUEUED,
        AttachmentTransferStateFfi.DOWNLOADING,
        AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT,
        AttachmentTransferStateFfi.DECRYPTING,
        AttachmentTransferStateFfi.VERIFYING_PLAINTEXT,
        AttachmentTransferStateFfi.RETRY_SCHEDULED,
        AttachmentTransferStateFfi.PAUSED,
        -> AttachmentTransferState.Downloading
        AttachmentTransferStateFfi.READY -> AttachmentTransferState.Available
        AttachmentTransferStateFfi.FAILED -> AttachmentTransferState.Failed
        AttachmentTransferStateFfi.CANCELLED -> AttachmentTransferState.Cancelled
    }

private interface NativeTransferFeed : Closeable {
    suspend fun next(): AttachmentTransferSnapshotFfi?
}

private class MarmotNativeTransferFeed(
    private val subscription: AttachmentTransferSubscription,
) : NativeTransferFeed {
    override suspend fun next(): AttachmentTransferSnapshotFfi? = subscription.next()

    override fun close() {
        subscription.cancel()
        subscription.close()
    }
}

/**
 * Owns one explicit native acquisition. Automatic Android policy remains on
 * the legacy WorkManager path because MarmotKit's global automatic flag cannot
 * express White Noise's per-type and per-network matrix.
 */
@Suppress("ReturnCount", "ThrowsCount") // Native terminal states map directly to explicit demand outcomes.
internal suspend fun WhiteNoiseAppState.acquireNativeAttachment(
    request: AttachmentTransferRequest,
    priority: AttachmentDownloadPriority,
): AttachmentPlaintext? {
    val target = request.nativeTarget()
    if (!shouldUseNativeExplicitDemand(priority, target)) return null
    requireNotNull(target)
    openNativeAttachment(request)?.let { return it }

    val ffiTarget = target.toFfi()
    val initial =
        marmotIo {
            attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(ffiTarget))
        }
    initial.items.singleOrNull()?.takeIf { it.state == AttachmentTransferStateFfi.READY }?.let {
        return openNativeAttachment(request)
    }

    val feed =
        MarmotNativeTransferFeed(
            marmotIo {
                subscribeAttachmentTransfers(request.accountRef, request.groupIdHex, listOf(ffiTarget))
            },
        )
    return try {
        marmotIo { downloadAttachmentAgain(request.accountRef, request.groupIdHex, ffiTarget) }
            ?: throw IOException("native attachment demand was rejected")
        feed.use { updates ->
            var ready = false
            while (!ready) {
                val status =
                    updates.next()?.items?.singleOrNull()
                        ?: throw IOException("native attachment transfer closed")
                if (status.state == AttachmentTransferStateFfi.READY) {
                    ready = true
                }
                if (status.state in NATIVE_TRANSFER_TERMINAL_FAILURES) {
                    throw IOException("native attachment transfer ended as ${status.state}")
                }
            }
        }
        openNativeAttachment(request)
            ?: throw IOException("native attachment was ready without readable bytes")
    } catch (cancelled: CancellationException) {
        cancelNativeAttachment(request)
        throw cancelled
    }
}

/** Cancels the current native job by its ephemeral reference, when one exists. */
@Suppress("ReturnCount") // Missing target/status/reference are distinct harmless stale-handle outcomes.
internal suspend fun WhiteNoiseAppState.cancelNativeAttachment(
    request: AttachmentTransferRequest,
): Boolean {
    val target = request.nativeTarget() ?: return false
    val status =
        marmotIo {
            attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(target.toFfi()))
        }.items.singleOrNull()
    val reference = status?.reference ?: return false
    return marmotIo { controlAttachment(request.accountRef, reference, AttachmentControlFfi.CANCEL) }
}

/** Materializes only bounded native plaintext for legacy byte-array consumers. */
internal suspend fun WhiteNoiseAppState.acquireNativeAttachmentBytes(
    request: AttachmentTransferRequest,
    priority: AttachmentDownloadPriority,
): ByteArray? =
    acquireNativeAttachment(request, priority)?.use { source ->
        if (source.size > NATIVE_ATTACHMENT_IN_MEMORY_LIMIT_BYTES) {
            throw IOException("native attachment exceeds the bounded in-memory consumer limit")
        }
        ByteArrayOutputStream(source.size.toInt()).use { output ->
            source.copyTo(output)
            output.toByteArray()
        }
    }
