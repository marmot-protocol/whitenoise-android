package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentControlFfi
import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.AttachmentTransferSubscription
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

private const val NATIVE_ATTACHMENT_IN_MEMORY_LIMIT_BYTES = 32L * 1024L * 1024L

private const val NATIVE_ATTACHMENT_CANCEL_TIMEOUT_MILLIS = 5_000L

private val NATIVE_TRANSFER_TERMINAL_FAILURES =
    setOf(
        AttachmentTransferStateFfi.UNAVAILABLE,
        AttachmentTransferStateFfi.FAILED,
        AttachmentTransferStateFfi.CANCELLED,
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.POLICY_BLOCKED,
        AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
        AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
        AttachmentTransferStateFfi.RETRY_EXHAUSTED,
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
        AttachmentTransferStateFfi.FAILED,
        AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
        AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
        AttachmentTransferStateFfi.RETRY_EXHAUSTED,
        -> AttachmentTransferState.Failed
        AttachmentTransferStateFfi.CANCELLED -> AttachmentTransferState.Cancelled
    }

internal interface NativeTransferFeed : Closeable {
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
 * Observes native-owned acquisition. Automatic demand never resets the native
 * retry budget; only an explicit user action starts a new acquisition cycle.
 * Closing an observer does not cancel durable native work.
 */
@Suppress("ReturnCount", "ThrowsCount") // Native terminal states map directly to explicit demand outcomes.
internal suspend fun WhiteNoiseAppState.acquireNativeAttachment(
    request: AttachmentTransferRequest,
    priority: AttachmentDownloadPriority,
    allowExplicitRetry: Boolean = true,
): AttachmentPlaintext? {
    val target =
        request.nativeTarget() ?: findNativeAttachment(request)?.target
            ?: throw AttachmentReferenceNotReadyException()
    val resolved = request.copy(sourceMessageIdHex = target.sourceMessageIdHex)
    openNativeAttachment(resolved)?.let { return it }

    val ffiTarget = target.toFfi()
    val initial =
        marmotIo {
            attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(ffiTarget))
        }
    initial.items.singleOrNull()?.takeIf { it.state == AttachmentTransferStateFfi.READY }?.let {
        openNativeAttachment(resolved)?.let { return it }
    }

    val feed =
        MarmotNativeTransferFeed(
            marmotIo {
                subscribeAttachmentTransfers(request.accountRef, request.groupIdHex, listOf(ffiTarget))
            },
        )
    awaitNativeAttachment(feed) {
        marmotIo {
            if (priority == AttachmentDownloadPriority.Automatic) {
                requestAutomaticAttachment(request.accountRef, request.groupIdHex, ffiTarget).status.state
            } else {
                val current =
                    attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(ffiTarget))
                        .items
                        .single()
                        .state
                if (!allowExplicitRetry && current != AttachmentTransferStateFfi.NOT_REQUESTED) return@marmotIo current
                downloadAttachmentAgain(request.accountRef, request.groupIdHex, ffiTarget)
                    ?: throw IOException("native attachment demand was rejected")
                null
            }
        }
    }
    return openNativeAttachment(resolved)
        ?: throw IOException("native attachment was ready without readable bytes")
}

/** Lexically owns observation even if demand fails or its caller stops waiting. */
internal suspend fun awaitNativeAttachment(
    feed: NativeTransferFeed,
    demand: suspend () -> AttachmentTransferStateFfi?,
) {
    feed.use { updates ->
        // The subscription starts with a pre-demand snapshot; it must not be
        // mistaken for the terminal outcome of the acquisition we are starting.
        updates.nextState()
        var state = demand()
        while (state != AttachmentTransferStateFfi.READY) {
            if (state in NATIVE_TRANSFER_TERMINAL_FAILURES) {
                throw NativeAttachmentTerminalException(requireNotNull(state))
            }
            state = updates.nextState()
        }
    }
}

/** A complete one-target replacement is required before interpreting native progress. */
private suspend fun NativeTransferFeed.nextState(): AttachmentTransferStateFfi =
    next()?.items?.singleOrNull()?.state ?: throw IOException("native attachment transfer closed")

/** A terminal native acquisition must not be turned into a host transport retry. */
internal class NativeAttachmentTerminalException(
    val state: AttachmentTransferStateFfi,
) : IllegalStateException("native attachment transfer ended as $state")

/** Sends native cancellation only for an explicit user action, bounded independently of caller cancellation. */
internal suspend fun WhiteNoiseAppState.cancelNativeAttachmentBounded(request: AttachmentTransferRequest) {
    withContext(NonCancellable) {
        withTimeoutOrNull(NATIVE_ATTACHMENT_CANCEL_TIMEOUT_MILLIS) {
            runCatching { cancelNativeAttachment(request) }
        }
    }
}

/** Cancels the current native job by its ephemeral reference, when one exists. */
@Suppress("ReturnCount") // Missing target/status/reference are distinct harmless stale-handle outcomes.
internal suspend fun WhiteNoiseAppState.cancelNativeAttachment(
    request: AttachmentTransferRequest,
): Boolean {
    val target = resolvedTarget ?: resolveNativeAttachmentTarget(request) ?: return false
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
        withContext(Dispatchers.IO) {
            ByteArrayOutputStream(source.size.toInt()).use { output ->
                source.copyTo(output)
                output.toByteArray()
            }
        }
    }
