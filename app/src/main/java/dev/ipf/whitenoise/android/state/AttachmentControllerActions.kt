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
        AttachmentTransferState.Cancelled,
        AttachmentTransferState.NotRetained,
    )

/** Admits a visible Retry/Download again gesture once, fencing delivery after navigation or cancellation. */
internal fun ConversationController.retryAttachmentTransfer(
    messageIdHex: String,
    attachmentIndex: Int,
    onAccepted: () -> Unit,
    onFailure: () -> Unit,
): Boolean {
    val request =
        attachmentTransferRequest(messageIdHex, attachmentIndex)
            ?.copy(sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex))
    val destination = attachmentOpenRequest(messageIdHex, attachmentIndex)
    val duplicate = request?.let { it.cacheKey() in appState.attachmentUserActions.pendingRetries.value } == true
    if (request == null || destination == null || duplicate) return false
    val token = appState.attachmentOpens.beginUserAction()
    return appState.retryAttachmentDownload(
        request,
        onAccepted = {
            val current = appState.attachmentOpens.isCurrentUserAction(token)
            if (current && appState.attachmentOpens.isVisible(destination)) onAccepted()
        },
        onFailure = { failure ->
            val current = appState.attachmentOpens.isCurrentUserAction(token)
            if (current && appState.attachmentOpens.isVisible(destination)) {
                deliverAttachmentRetryFailure(
                    failure,
                    reopen = { requestAttachmentOpen(messageIdHex, attachmentIndex) },
                    notify = {
                        appState.present(
                            appState.appContext.getString(R.string.media_couldnt_load),
                            copyable = true,
                        )
                    },
                )
                onFailure()
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

/** An actual Retry/Download again gesture may recover terminal work; ordinary Open/Save only joins. */
internal fun ConversationController.performAttachmentUserAction(
    messageIdHex: String,
    attachmentIndex: Int,
    state: AttachmentTransferState,
    onAccepted: () -> Unit,
    onFailure: () -> Unit,
) {
    if (state in DELIBERATE_RECOVERY_STATES ||
        automaticAttachmentDownloadSuppressed(messageIdHex, attachmentIndex)
    ) {
        retryAttachmentTransfer(messageIdHex, attachmentIndex, onAccepted, onFailure)
    } else {
        onAccepted()
    }
}

/** Exposes acknowledgement separately from host detachment so a spinner cannot falsely imply a stopped socket. */
internal fun ConversationController.attachmentCancellationState(
    messageIdHex: String,
    attachmentIndex: Int,
) = attachmentTransfers.cancellationState(attachmentTransferKey(messageIdHex, attachmentIndex))

/** Native terminal failures can be shown before a user has started a host plaintext waiter. */
internal fun attachmentFilePresentationState(
    host: AttachmentTransferState,
    native: NativeAttachmentProgress?,
    cancellation: AttachmentCancellationState,
): AttachmentTransferState =
    when {
        host == AttachmentTransferState.Available || cancellation != AttachmentCancellationState.None -> host
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
    }

/** An unresolved projection needs the existing open/reload path; other failures remain visible to the user. */
internal fun deliverAttachmentRetryFailure(
    failure: Throwable,
    reopen: () -> Boolean,
    notify: () -> Unit,
) {
    if (failure !is AttachmentReferenceNotReadyException || !reopen()) notify()
}
