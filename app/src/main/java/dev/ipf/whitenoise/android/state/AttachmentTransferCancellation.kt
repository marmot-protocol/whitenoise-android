package dev.ipf.whitenoise.android.state

/** Revokes platform delivery immediately and renders Cancelled only after native acknowledgement. */
internal fun ConversationController.cancelAttachmentTransfer(
    messageIdHex: String,
    attachmentIndex: Int,
) {
    val openRequest = attachmentOpenRequest(messageIdHex, attachmentIndex)
    val transferRequest = attachmentTransferRequest(messageIdHex, attachmentIndex)
    openRequest?.let { appState.attachmentOpens.cancelOpen(it) }
    transferRequest?.let { appState.attachmentInstallerHandoffs.cancel(it) }
    val onNativeResult =
        attachmentTransfers.cancel(
            attachmentTransferKey(messageIdHex, attachmentIndex),
            nativeActive = true,
        )
    boundAccountRef?.let { account ->
        appState.cancelAttachmentDownload(
            AttachmentTransferRequest(
                accountRef = account,
                groupIdHex = group.groupIdHex,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex),
            ),
            onNativeResult = onNativeResult,
        )
    } ?: onNativeResult(false)
}

/** True while the user's cancel of this attachment still blocks the automatic path. */
internal fun ConversationController.automaticAttachmentDownloadSuppressed(
    messageIdHex: String,
    attachmentIndex: Int,
): Boolean {
    val account = boundAccountRef ?: return false
    return appState.automaticAttachmentDownloadSuppressed(
        AttachmentTransferRequest(
            accountRef = account,
            groupIdHex = group.groupIdHex,
            messageIdHex = messageIdHex,
            attachmentIndex = attachmentIndex,
            sourceMessageIdHex = nativeAttachmentSourceId(messageIdHex),
        ),
    )
}
