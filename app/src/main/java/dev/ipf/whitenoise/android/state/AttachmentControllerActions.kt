package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private val DELIBERATE_RECOVERY_STATES =
    setOf(
        AttachmentTransferState.Failed,
        AttachmentTransferState.StorageBlocked,
        AttachmentTransferState.Cancelled,
        AttachmentTransferState.NotRetained,
    )

/**
 * Admits a visible Retry/Download again gesture once, fencing delivery after navigation or cancellation. When the
 * engine has not projected the attachment's reference yet, [onReferenceNotReady] re-arms the download instead of
 * reporting a failure; by default that is the durable in-place download, which never opens the attachment.
 */
internal fun ConversationController.retryAttachmentTransfer(
    messageIdHex: String,
    attachmentIndex: Int,
    onAccepted: () -> Unit,
    onReferenceNotReady: () -> Boolean = { requestAttachmentDownload(messageIdHex, attachmentIndex) },
    onFailure: (Throwable) -> Unit,
): Boolean {
    val request =
        attachmentTransferRequest(messageIdHex, attachmentIndex)
            ?.copy(sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex))
    val destination = attachmentOpenRequest(messageIdHex, attachmentIndex)
    val duplicate = request?.let { it.cacheKey() in appState.attachmentUserActions.pendingRetries.value } == true
    if (
        request == null ||
        !acceptsConversationActionOwner(request.accountRef, request.groupIdHex) ||
        duplicate
    ) {
        return false
    }
    val canDeliver = {
        acceptsConversationActionOwner(request.accountRef, request.groupIdHex) &&
            (destination == null || appState.attachmentOpens.isVisible(destination))
    }
    val token = appState.attachmentOpens.beginUserAction()
    return appState.retryAttachmentDownload(
        request,
        onAccepted = {
            val current = appState.attachmentOpens.isCurrentUserAction(token)
            if (current && canDeliver()) onAccepted()
        },
        onFailure = { failure ->
            val current = appState.attachmentOpens.isCurrentUserAction(token)
            if (current && canDeliver()) {
                deliverAttachmentRetryFailure(
                    failure,
                    rearmDownload = { destination != null && onReferenceNotReady() },
                    notify = {
                        appState.present(R.string.media_couldnt_load)
                        onFailure(failure)
                    },
                )
            }
        },
    )
}

/** Exposes pending user admission without giving composition ownership of the native command. */
internal fun ConversationController.attachmentRetryPending(
    messageIdHex: String,
    attachmentIndex: Int,
): Flow<Boolean> {
    val request = attachmentTransferRequest(messageIdHex, attachmentIndex) ?: return emptyFlow()
    return appState.attachmentUserActions.pendingRetries
        .map { request.cacheKey() in it }
        .distinctUntilChanged()
}

/** Native progress is observed only while the visible destination subscribes. */
internal fun ConversationController.attachmentNativeProgress(
    messageIdHex: String,
    attachmentIndex: Int,
): Flow<NativeAttachmentProgress?> {
    val request =
        attachmentTransferRequest(messageIdHex, attachmentIndex)
            ?.copy(sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex)) ?: return emptyFlow()
    return appState.nativeProgress(request).onEach { progress ->
        if (progress?.phase == dev.ipf.marmotkit.AttachmentTransferStateFfi.READY) {
            refreshAttachmentTransferState(messageIdHex, attachmentIndex)
        }
    }
}

/** Records that the reader's own download of this attachment is parked for storage, so reopening can restore it. */
internal fun ConversationController.recordReaderParkedDownload(
    messageIdHex: String,
    attachmentIndex: Int,
) {
    attachmentTransferRequest(messageIdHex, attachmentIndex)?.let { appState.recordReaderParkedDownload(it) }
}

/** Whether the reader's own download of this attachment is still parked for storage; reads, never demands. */
internal suspend fun ConversationController.readerDownloadParkedForStorage(
    messageIdHex: String,
    attachmentIndex: Int,
): Boolean {
    val request =
        attachmentTransferRequest(messageIdHex, attachmentIndex)
            ?.copy(sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex)) ?: return false
    return appState.readerDownloadParkedForStorage(request)
}

/**
 * An actual Retry/Download again gesture may recover terminal work; ordinary Open/Save only joins. A reference the
 * engine has not projected yet re-runs [onAccepted], the caller's own download, rather than reporting a failure.
 */
internal fun ConversationController.performAttachmentUserAction(
    messageIdHex: String,
    attachmentIndex: Int,
    state: AttachmentTransferState,
    onAccepted: () -> Unit,
    onFailure: () -> Unit,
) {
    if (attachmentActionNeedsRetry(state, automaticAttachmentDownloadSuppressed(messageIdHex, attachmentIndex))) {
        retryAttachmentTransfer(
            messageIdHex,
            attachmentIndex,
            onAccepted = onAccepted,
            onReferenceNotReady = {
                onAccepted()
                true
            },
        ) { onFailure() }
    } else {
        onAccepted()
    }
}

/** Cancellation suppression blocks automatic restart without turning a verified local Open into Retry. */
internal fun attachmentActionNeedsRetry(
    state: AttachmentTransferState,
    automaticSuppressed: Boolean,
): Boolean = state != AttachmentTransferState.Available && (state in DELIBERATE_RECOVERY_STATES || automaticSuppressed)

/** Exposes acknowledgement separately from host detachment so a spinner cannot falsely imply a stopped socket. */
internal fun ConversationController.attachmentCancellationState(
    messageIdHex: String,
    attachmentIndex: Int,
) = attachmentTransfers.cancellationState(attachmentTransferKey(messageIdHex, attachmentIndex))

/** Shows native progress and failures without letting passive observations re-arm trimmed plaintext. */
internal fun attachmentFilePresentationState(
    host: AttachmentTransferState,
    native: NativeAttachmentProgress?,
    cancellation: AttachmentCancellationState,
): AttachmentTransferState =
    when {
        host == AttachmentTransferState.Available ||
            host == AttachmentTransferState.Cancelled ||
            cancellation != AttachmentCancellationState.None -> host
        native == null -> host
        native.phase == dev.ipf.marmotkit.AttachmentTransferStateFfi.READY -> host
        else ->
            dev.ipf.marmotkit
                .AttachmentTransferStatusFfi(
                    null,
                    native.phase,
                    native.attempt,
                    native.received,
                    native.total,
                    native.retryAt,
                ).toPresentationState()
                .let { projected ->
                    if (host == AttachmentTransferState.NotRetained && projected == AttachmentTransferState.Remote) {
                        host
                    } else {
                        projected
                    }
                }
    }

/** An unresolved projection re-arms the in-place download; other failures remain visible to the user. */
internal fun deliverAttachmentRetryFailure(
    failure: Throwable,
    rearmDownload: () -> Boolean,
    notify: () -> Unit,
) {
    if (failure !is AttachmentReferenceNotReadyException || !rearmDownload()) notify()
}
