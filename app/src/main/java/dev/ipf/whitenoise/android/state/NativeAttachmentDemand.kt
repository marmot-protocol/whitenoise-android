package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.MarmotInterface
import java.io.IOException

private val NATIVE_TRANSFER_DELIBERATE_RETRY_STATES =
    setOf(
        AttachmentTransferStateFfi.FAILED,
        AttachmentTransferStateFfi.CANCELLED,
        AttachmentTransferStateFfi.REMOVED,
        AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
        AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
        AttachmentTransferStateFfi.RETRY_EXHAUSTED,
    )

/** Host action intent; observing or joining native work never authorizes a retry-budget reset. */
internal enum class AttachmentDemandIntent {
    Join,
    Observe,
    Retry,
}

/** Admits one deliberate retry before platform work or a viewer can observe the replacement acquisition. */
internal suspend fun WhiteNoiseAppState.requestAttachmentRetry(request: AttachmentTransferRequest) {
    val target = resolveNativeAttachmentTarget(request) ?: throw AttachmentReferenceNotReadyException()
    val resolved = request.copy(sourceMessageIdHex = target.sourceMessageIdHex)
    if (hasNativeAttachment(resolved)) return
    marmotIo {
        val state =
            requestNativeInteractiveAttachment(
                request.accountRef,
                request.groupIdHex,
                target.toFfi(),
                AttachmentDemandIntent.Retry,
            )
        if (state in NATIVE_TRANSFER_TERMINAL_FAILURES) {
            throw NativeAttachmentTerminalException(requireNotNull(state))
        }
    }
}

/** Reads native admission state after idempotent demand so an already-ready transfer never waits for another update. */
internal suspend fun MarmotInterface.requestNativeInteractiveAttachment(
    accountRef: String,
    groupIdHex: String,
    target: AttachmentLocalTargetFfi,
    demandIntent: AttachmentDemandIntent,
): AttachmentTransferStateFfi? =
    requestInteractiveAttachmentDemand(
        current = attachmentTransferSnapshot(accountRef, groupIdHex, listOf(target)).items.single().state,
        demandIntent = demandIntent,
        requestExplicit = {
            requestExplicitAttachment(accountRef, groupIdHex, target)
            nativeDemandAdmissionState(accountRef, groupIdHex, target)
        },
        retryTerminal = {
            downloadAttachmentAgain(accountRef, groupIdHex, target)
                ?: throw IOException("native attachment demand was rejected")
            nativeDemandAdmissionState(accountRef, groupIdHex, target)
        },
    )

/** Refused admission cannot create a host waiter with no future native update to await. */
private suspend fun MarmotInterface.nativeDemandAdmissionState(
    accountRef: String,
    groupIdHex: String,
    target: AttachmentLocalTargetFfi,
): AttachmentTransferStateFfi {
    val fresh = attachmentTransferSnapshot(accountRef, groupIdHex, listOf(target)).items.single().state
    if (fresh == AttachmentTransferStateFfi.NOT_REQUESTED) {
        throw IOException("native attachment demand was not admitted")
    }
    return fresh
}

/**
 * Separates joining current demand from a deliberate retry of terminal work.
 * The caller must supply retry permission from a deliberate user action, not
 * infer that permission from an Android worker's attempt count or native state.
 */
internal suspend fun requestInteractiveAttachmentDemand(
    current: AttachmentTransferStateFfi,
    demandIntent: AttachmentDemandIntent,
    requestExplicit: suspend () -> AttachmentTransferStateFfi?,
    retryTerminal: suspend () -> AttachmentTransferStateFfi?,
): AttachmentTransferStateFfi? =
    when {
        demandIntent == AttachmentDemandIntent.Observe && current != AttachmentTransferStateFfi.NOT_REQUESTED -> current
        demandIntent != AttachmentDemandIntent.Retry && current in NATIVE_TRANSFER_DELIBERATE_RETRY_STATES -> current
        current in NATIVE_TRANSFER_DELIBERATE_RETRY_STATES -> retryTerminal()
        else -> requestExplicit()
    }
