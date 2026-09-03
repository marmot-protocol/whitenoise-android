package dev.ipf.whitenoise.android.state

/**
 * Cancels one queued or running attachment transfer from its file bubble.
 *
 * The durable intents are revoked before the in-memory owner, so a worker retry
 * or a process restoration that lands between the two steps cannot resurrect a
 * cancelled transfer. [AttachmentTransferCoordinator.cancel] then publishes a
 * stable [AttachmentTransferState.Cancelled] and documents what an in-flight
 * MDK fetch may still do.
 */
internal fun ConversationController.cancelAttachmentTransfer(
    messageIdHex: String,
    attachmentIndex: Int,
) {
    val openRequest = attachmentOpenRequest(messageIdHex, attachmentIndex)
    val transferRequest = attachmentTransferRequest(messageIdHex, attachmentIndex)
    boundAccountRef?.let { account ->
        appState.cancelAttachmentDownload(
            AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex),
        )
    }
    attachmentTransfers.cancel(attachmentTransferKey(messageIdHex, attachmentIndex))
    openRequest?.let { appState.attachmentOpens.cancelOpen(it) }
    transferRequest?.let { appState.attachmentInstallerHandoffs.cancel(it) }
}

/** True while the user's cancel of this attachment still blocks the automatic path. */
internal fun ConversationController.automaticAttachmentDownloadSuppressed(
    messageIdHex: String,
    attachmentIndex: Int,
): Boolean {
    val account = boundAccountRef ?: return false
    return appState.automaticAttachmentDownloadSuppressed(
        AttachmentTransferRequest(account, group.groupIdHex, messageIdHex, attachmentIndex),
    )
}
