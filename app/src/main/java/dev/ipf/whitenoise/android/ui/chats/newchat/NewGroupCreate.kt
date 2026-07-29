package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.R

internal enum class GroupRetentionApplyOutcome {
    Skipped,
    Applied,
    Failed,
}

/**
 * Returns the post-create success toast, or null when retention failed so the
 * failure toast is not overwritten by the single snackbar slot.
 */
internal fun groupCreateSuccessToastResId(
    showCreatedToast: Boolean,
    retentionOutcome: GroupRetentionApplyOutcome,
): Int? =
    when {
        !showCreatedToast -> null
        retentionOutcome == GroupRetentionApplyOutcome.Failed -> null
        else -> R.string.toast_chat_created
    }

internal data class NewGroupSetupUiState(
    val detailsEditable: Boolean,
    val fabLabelResId: Int,
    val statusResId: Int?,
    val submitEnabled: Boolean,
)

internal fun canStartNewGroupCreateAttempt(
    busy: Boolean,
    canCreate: Boolean,
    retryGroupIdHex: String?,
): Boolean = !busy && (retryGroupIdHex != null || canCreate)

/**
 * Once MLS create succeeded ([retryGroupIdHex] set) but opening failed, the
 * screen is a retry-open surface — not an editable create form (#1729).
 */
internal fun newGroupSetupUiState(
    retryGroupIdHex: String?,
    canCreate: Boolean,
    busy: Boolean,
): NewGroupSetupUiState =
    if (retryGroupIdHex != null) {
        NewGroupSetupUiState(
            detailsEditable = false,
            fabLabelResId = R.string.retry,
            statusResId = if (busy) null else R.string.error_chat_created_not_loaded,
            submitEnabled = canStartNewGroupCreateAttempt(busy, canCreate, retryGroupIdHex),
        )
    } else {
        NewGroupSetupUiState(
            detailsEditable = true,
            fabLabelResId = R.string.create,
            statusResId = null,
            submitEnabled = canStartNewGroupCreateAttempt(busy, canCreate, retryGroupIdHex),
        )
    }
