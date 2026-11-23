package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import dev.ipf.marmotkit.Nip46StateFfi
import dev.ipf.marmotkit.Nip46StatusFfi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** How often pairing re-reads MDK's local status to catch a browser approval request. */
private const val APPROVAL_POLL_MS = 500L

/** What the signer's current status means for an on-screen browser check. */
internal sealed interface ApprovalSignal {
    /** No check is pending; any shown prompt is stale. */
    data object Cleared : ApprovalSignal

    /** A check whose page is not https; it is refused, never opened. */
    data object Refused : ApprovalSignal

    data class Requested(
        val approval: RemoteSignerStep.Approval,
    ) : ApprovalSignal
}

/** Re-reads MDK's local status through [readStatus] while [isCurrent]; reports what it means for the prompt. */
internal suspend fun awaitSignerApproval(
    readStatus: suspend () -> Nip46StatusFfi,
    isCurrent: () -> Boolean,
    onSignal: (ApprovalSignal) -> Unit,
) {
    while (currentCoroutineContext().isActive && isCurrent()) {
        val status = readStatus()
        val url = status.authUrl
        val request = status.requestId
        val signal =
            if (status.state == Nip46StateFfi.APPROVAL && url != null && request != null) {
                remoteSignerApprovalHost(url)
                    ?.let { ApprovalSignal.Requested(RemoteSignerStep.Approval(url, it, request)) }
                    ?: ApprovalSignal.Refused
            } else {
                ApprovalSignal.Cleared
            }
        onSignal(signal)
        delay(APPROVAL_POLL_MS)
    }
}
