package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentControlFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val NATIVE_ATTACHMENT_CANCEL_TIMEOUT_MILLIS = 5_000L

/** Sends native cancellation only for an explicit user action, bounded independently of caller cancellation. */
internal suspend fun WhiteNoiseAppState.cancelNativeAttachmentBounded(request: AttachmentTransferRequest): Boolean =
    withContext(NonCancellable) {
        withTimeoutOrNull(NATIVE_ATTACHMENT_CANCEL_TIMEOUT_MILLIS) {
            runCatching { cancelNativeAttachment(request) }.getOrDefault(false)
        } ?: false
    }

/** Cancels the current native job by its ephemeral reference, when one exists. */
@Suppress("ReturnCount") // Missing target/status/reference are distinct harmless stale-handle outcomes.
internal suspend fun WhiteNoiseAppState.cancelNativeAttachment(
    request: AttachmentTransferRequest,
    resolvedTarget: NativeAttachmentTarget? = null,
): Boolean {
    val target = resolvedTarget ?: resolveNativeAttachmentTarget(request) ?: return false
    return marmotIo {
        val status =
            attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(target.toFfi()))
                .items
                .singleOrNull()
        nativeAttachmentCancellationOutcome(status) { reference ->
            controlAttachment(request.accountRef, reference, AttachmentControlFfi.CANCEL)
        } != NativeAttachmentCancellationOutcome.Unconfirmed
    }
}

/** Separates a verified absence of acquisition from acknowledged cancellation and an unknown result. */
internal enum class NativeAttachmentCancellationOutcome { NoWork, Acknowledged, Unconfirmed }

/** Only a canonical NOT_REQUESTED snapshot proves there is no work; missing identity or status stays unconfirmed. */
internal suspend fun nativeAttachmentCancellationOutcome(
    status: AttachmentTransferStatusFfi?,
    cancel: suspend (String) -> Boolean,
): NativeAttachmentCancellationOutcome =
    when {
        status == null -> NativeAttachmentCancellationOutcome.Unconfirmed
        status.state == AttachmentTransferStateFfi.NOT_REQUESTED -> NativeAttachmentCancellationOutcome.NoWork
        status.reference == null -> NativeAttachmentCancellationOutcome.Unconfirmed
        cancel(requireNotNull(status.reference)) -> NativeAttachmentCancellationOutcome.Acknowledged
        else -> NativeAttachmentCancellationOutcome.Unconfirmed
    }
