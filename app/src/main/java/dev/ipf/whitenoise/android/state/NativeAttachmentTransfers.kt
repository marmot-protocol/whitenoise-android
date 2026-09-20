package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentControlFfi
import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.AttachmentTransferSubscription
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException

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
    /** Waits for a native replacement without granting or cancelling acquisition. */
    override suspend fun next(): AttachmentTransferSnapshotFfi? = subscription.next()

    /** Wakes pending observation and releases its native handle. */
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
): AttachmentTransferRequest {
    val target =
        request.nativeTarget() ?: findNativeAttachment(request)?.target
            ?: throw AttachmentReferenceNotReadyException()
    val resolved = request.copy(sourceMessageIdHex = target.sourceMessageIdHex)
    if (hasNativeAttachment(resolved)) return resolved

    val ffiTarget = target.toFfi()
    val initial =
        marmotIo {
            attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(ffiTarget))
        }
    initial.items.singleOrNull()?.takeIf { it.state == AttachmentTransferStateFfi.READY }?.let {
        if (hasNativeAttachment(resolved)) return resolved
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
    return resolved
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
    resolvedTarget: NativeAttachmentTarget? = null,
): Boolean {
    val target = resolvedTarget ?: resolveNativeAttachmentTarget(request) ?: return false
    val status =
        marmotIo {
            attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(target.toFfi()))
        }.items.singleOrNull()
    val reference = status?.reference ?: return false
    return marmotIo { controlAttachment(request.accountRef, reference, AttachmentControlFfi.CANCEL) }
}
