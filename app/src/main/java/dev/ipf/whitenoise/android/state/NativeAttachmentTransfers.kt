package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.AttachmentTransferSubscription
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException

internal val NATIVE_TRANSFER_TERMINAL_FAILURES =
    setOf(
        AttachmentTransferStateFfi.UNAVAILABLE,
        AttachmentTransferStateFfi.FAILED,
        AttachmentTransferStateFfi.CANCELLED,
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.CLEANED_BY_SETTING,
        AttachmentTransferStateFfi.POLICY_BLOCKED,
        AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
        AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
        AttachmentTransferStateFfi.RETRY_EXHAUSTED,
        // MDK readmits a storage-parked body itself once space frees, so observation ends rather than waiting.
        AttachmentTransferStateFfi.STORAGE_BLOCKED,
    )

/**
 * How long durable automatic observation lets MDK keep a transfer paused before it frees its download slot. A
 * network change pauses automatic work and resumes it about 15 seconds later; a transfer whose type was turned
 * off stays paused until the type is allowed again.
 */
internal const val NATIVE_AUTOMATIC_PAUSE_GRACE_MILLIS = 60_000L

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
        AttachmentTransferStateFfi.RETRY_EXHAUSTED,
        -> AttachmentTransferState.Failed
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.CLEANED_BY_SETTING,
        AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
        AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
        -> AttachmentTransferState.NotRetained
        AttachmentTransferStateFfi.CANCELLED -> AttachmentTransferState.Cancelled
        AttachmentTransferStateFfi.STORAGE_BLOCKED -> AttachmentTransferState.StorageBlocked
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

/** Opens a one-target subscription; its caller owns lexical disposal without cancelling acquisition. */
internal suspend fun WhiteNoiseAppState.openNativeAttachmentFeed(
    request: AttachmentTransferRequest,
    target: NativeAttachmentTarget,
): NativeTransferFeed =
    MarmotNativeTransferFeed(
        marmotIo { subscribeAttachmentTransfers(request.accountRef, request.groupIdHex, listOf(target.toFfi())) },
    )

/**
 * Observes native-owned acquisition. Interactive demand joins live work;
 * only the explicit Retry intent permits terminal recovery.
 * Closing an observer does not cancel durable native work.
 */
@Suppress("ReturnCount", "ThrowsCount") // Native terminal states map directly to explicit demand outcomes.
internal suspend fun WhiteNoiseAppState.acquireNativeAttachment(
    request: AttachmentTransferRequest,
    priority: AttachmentDownloadPriority,
    demandIntent: AttachmentDemandIntent = AttachmentDemandIntent.Join,
    diagnostics: AttachmentFetchDiagnostics? = null,
): AttachmentTransferRequest {
    val target =
        request.nativeTarget() ?: findNativeAttachment(request)?.target
            ?: throw AttachmentReferenceNotReadyException()
    val resolved = request.copy(sourceMessageIdHex = target.sourceMessageIdHex)
    if (hasNativeAttachment(resolved)) return resolved

    val ffiTarget = target.toFfi()
    diagnostics?.phase(
        phase = PerformancePhase.ATTACHMENT_NATIVE_SNAPSHOT,
        result = PerformanceResult.PENDING,
        layer = PerformanceLayer.FFI,
    )
    val initial =
        marmotIo {
            attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(ffiTarget))
        }
    initial.items
        .singleOrNull()
        ?.also { diagnostics?.transferUpdate(it.state) }
        ?.takeIf {
            it.state == AttachmentTransferStateFfi.READY
        }?.let {
            if (hasNativeAttachment(resolved)) return resolved
        }

    awaitNativeAttachment(
        open = {
            MarmotNativeTransferFeed(
                marmotIo {
                    subscribeAttachmentTransfers(request.accountRef, request.groupIdHex, listOf(ffiTarget))
                },
            )
        },
        onDemand = {
            diagnostics?.phase(
                phase = PerformancePhase.ATTACHMENT_NATIVE_DEMAND,
                result = PerformanceResult.PENDING,
                layer = PerformanceLayer.FFI,
            )
        },
        onState = { state -> diagnostics?.transferUpdate(state) },
        pausedGraceMillis = automaticPauseGrace(priority, demandIntent),
    ) {
        marmotIo {
            if (priority == AttachmentDownloadPriority.Automatic) {
                requestAutomaticAttachment(request.accountRef, request.groupIdHex, ffiTarget).status.state
            } else {
                requestNativeInteractiveAttachment(
                    request.accountRef,
                    request.groupIdHex,
                    ffiTarget,
                    demandIntent,
                )
            }
        }
    }
    return resolved
}

/** Only durable work observes, and only it gives up on a lasting pause: a bubble keeps showing the pause. */
private fun automaticPauseGrace(
    priority: AttachmentDownloadPriority,
    demandIntent: AttachmentDemandIntent,
): Long? =
    NATIVE_AUTOMATIC_PAUSE_GRACE_MILLIS.takeIf {
        priority == AttachmentDownloadPriority.Automatic && demandIntent == AttachmentDemandIntent.Observe
    }

/**
 * Installs native subscription ownership before caller cancellation can resume, then always releases it. A
 * non-null [pausedGraceMillis] ends observation of a transfer that stays paused that long.
 */
internal suspend fun awaitNativeAttachment(
    open: suspend () -> NativeTransferFeed,
    onDemand: () -> Unit = {},
    onState: (AttachmentTransferStateFfi) -> Unit = {},
    pausedGraceMillis: Long? = null,
    demand: suspend () -> AttachmentTransferStateFfi?,
) {
    var owned: NativeTransferFeed? = null
    try {
        withContext(NonCancellable) { owned = open() }
        observeNativeAttachment(checkNotNull(owned), demand, onDemand, onState, pausedGraceMillis)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { owned?.close() }
    }
}

/** Lexically owns observation even if demand fails or its caller stops waiting. */
internal suspend fun awaitNativeAttachment(
    feed: NativeTransferFeed,
    onDemand: () -> Unit = {},
    onState: (AttachmentTransferStateFfi) -> Unit = {},
    demand: suspend () -> AttachmentTransferStateFfi?,
) {
    feed.use { observeNativeAttachment(it, demand, onDemand, onState, pausedGraceMillis = null) }
}

/** Waits for one demanded acquisition after ownership has already been made cancellation-safe. */
private suspend fun observeNativeAttachment(
    updates: NativeTransferFeed,
    demand: suspend () -> AttachmentTransferStateFfi?,
    onDemand: () -> Unit,
    onState: (AttachmentTransferStateFfi) -> Unit,
    pausedGraceMillis: Long?,
) {
    // The subscription starts with a pre-demand snapshot; it must not be
    // mistaken for the terminal outcome of the acquisition we are starting.
    updates.nextState().also(onState)
    onDemand()
    // MDK delivers a state change, READY included, as soon as it happens; only byte progress is spaced 250 ms apart.
    var state = demand()?.also(onState)
    while (state != AttachmentTransferStateFfi.READY) {
        if (state in NATIVE_TRANSFER_TERMINAL_FAILURES) {
            throw NativeAttachmentTerminalException(requireNotNull(state))
        }
        state =
            if (state == AttachmentTransferStateFfi.PAUSED && pausedGraceMillis != null) {
                updates.awaitPauseEnd(onState, pausedGraceMillis)
            } else {
                updates.nextState().also(onState)
            }
    }
}

/** Returns the first state after a pause, or ends observation once the pause has lasted [graceMillis]. */
private suspend fun NativeTransferFeed.awaitPauseEnd(
    onState: (AttachmentTransferStateFfi) -> Unit,
    graceMillis: Long,
): AttachmentTransferStateFfi =
    withTimeoutOrNull(graceMillis) {
        var next = AttachmentTransferStateFfi.PAUSED
        while (next == AttachmentTransferStateFfi.PAUSED) next = nextState().also(onState)
        next
    } ?: throw NativeAttachmentPausedException()

/** A complete one-target replacement is required before interpreting native progress. */
internal suspend fun NativeTransferFeed.nextState(): AttachmentTransferStateFfi =
    // UniFFI wakes under a scheduler lock; dispatch before a resumed read polls it again.
    withContext(Dispatchers.IO) {
        next()?.items?.singleOrNull()?.state ?: throw IOException("native attachment transfer closed")
    }

/**
 * Durable automatic work MDK kept paused past [NATIVE_AUTOMATIC_PAUSE_GRACE_MILLIS]. Ending observation frees the
 * download slot it held; the worker then re-checks the live policy before asking again.
 */
internal class NativeAttachmentPausedException : IOException("native attachment transfer stayed paused")

/** A terminal native acquisition must not be turned into a host transport retry. */
internal class NativeAttachmentTerminalException(
    val state: AttachmentTransferStateFfi,
) : IllegalStateException("native attachment transfer ended as $state")
