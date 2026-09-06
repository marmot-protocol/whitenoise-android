package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi

/**
 * Incorporates a newly created identity without duplicating either its local
 * account label or its case-insensitive public identity. The caller publishes
 * this result under the account-list staleness guard.
 */
internal fun accountSummariesWithCreatedIdentity(
    current: List<AccountSummaryFfi>,
    created: AccountSummaryFfi,
): List<AccountSummaryFfi> {
    val existingIndex =
        current.indexOfFirst {
            it.label == created.label || it.accountIdHex.equals(created.accountIdHex, ignoreCase = true)
        }
    if (existingIndex < 0) return current + created
    return current.toMutableList().also { it[existingIndex] = created }
}

/** Accepts an upload result only for the account session that launched it. */
internal fun shouldAcceptMediaUploadForAccount(
    conversationAccountRef: String?,
    capturedMediaUploadSessionEpoch: Long,
    activeAccountRef: String?,
    currentMediaUploadSessionEpoch: Long,
): Boolean =
    conversationAccountRef != null &&
        conversationAccountRef == activeAccountRef &&
        capturedMediaUploadSessionEpoch == currentMediaUploadSessionEpoch
