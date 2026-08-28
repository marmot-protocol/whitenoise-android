package dev.ipf.whitenoise.android.state

/** The exact visible conversation session allowed to dispatch a persisted attachment open. */
internal data class AttachmentOpenDestination(
    val accountRef: String,
    val groupIdHex: String,
    val navigationGeneration: Long,
) {
    fun matches(request: AttachmentTransferRequest): Boolean =
        accountRef == request.accountRef &&
            groupIdHex.equals(request.groupIdHex, ignoreCase = true)
}

/** A transfer identity plus the navigation session in which the user tapped it. */
internal data class AttachmentOpenRequest(
    val transferRequest: AttachmentTransferRequest,
    val navigationGeneration: Long,
) {
    val destination: AttachmentOpenDestination
        get() =
            AttachmentOpenDestination(
                accountRef = transferRequest.accountRef,
                groupIdHex = transferRequest.groupIdHex,
                navigationGeneration = navigationGeneration,
            )
}

internal fun attachmentOpenDestinationVisible(
    selectedDestination: AttachmentOpenDestination?,
    request: AttachmentOpenRequest,
    appInForeground: Boolean,
    activeAccountRef: String?,
    activeGroupIdHex: String?,
): Boolean =
    appInForeground &&
        selectedDestination == request.destination &&
        activeAccountRef == request.transferRequest.accountRef &&
        activeGroupIdHex?.equals(request.transferRequest.groupIdHex, ignoreCase = true) == true
