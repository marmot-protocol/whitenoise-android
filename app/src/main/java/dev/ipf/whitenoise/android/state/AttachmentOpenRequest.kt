package dev.ipf.whitenoise.android.state

import java.util.UUID

/**
 * Starts each cold shell session in a disjoint generation range while remaining
 * saveable across activity and process-state restoration. The sign bit also
 * prevents collision with legacy zero-based generations already on disk.
 */
internal fun newAttachmentOpenNavigationGeneration(sessionId: UUID = UUID.randomUUID()): Long =
    (sessionId.mostSignificantBits xor sessionId.leastSignificantBits) or Long.MIN_VALUE

/** Null route slots never constitute the same visible conversation. */
internal fun attachmentOpenChatSelectionMatches(
    selectedChatId: String?,
    controllerChatId: String?,
): Boolean = selectedChatId != null && controllerChatId != null && selectedChatId == controllerChatId

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
