package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R

/**
 * The wording of one chat-list local removal: its failure title and detail, and the confirmation of a success. A
 * failed removal's retry keeps the same wording, so a retried abandonment never reads as a delete (#3310).
 */
internal data class LocalRemovalWording(
    @StringRes val failureTitle: Int,
    @StringRes val failureDetail: Int,
    @StringRes val success: Int,
) {
    companion object {
        /** Delete from this device, which keeps protocol membership and can receive the chat again. */
        val Delete =
            LocalRemovalWording(
                failureTitle = R.string.toast_couldnt_delete_chat,
                failureDetail = R.string.local_delete_retry_detail,
                success = R.string.toast_chat_deleted_local,
            )

        /** MDK's forget: abandoning a direct chat, or resetting a group to wait for a new invite. */
        fun abandonment(isDm: Boolean): LocalRemovalWording =
            if (isDm) {
                LocalRemovalWording(
                    failureTitle = R.string.toast_couldnt_abandon_chat,
                    failureDetail = R.string.local_abandon_retry_detail,
                    success = R.string.toast_chat_abandoned,
                )
            } else {
                LocalRemovalWording(
                    failureTitle = R.string.toast_couldnt_reset_group,
                    failureDetail = R.string.local_reset_retry_detail,
                    success = R.string.toast_group_reset_done,
                )
            }
    }
}

/** UI-only identity for retiring a resolved deletion warning without clearing unrelated failures. */
data class LocalDeleteNotice(
    val accountRef: String,
    val groupIds: Set<String>,
    val retry: ((Set<String>) -> Unit)? = null,
)

internal fun WhiteNoiseAppState.presentLocalDeleteFailure(
    @StringRes titleRes: Int,
    failure: Throwable?,
    detail: AppText = AppText.Resource(R.string.local_delete_retry_detail),
    notice: LocalDeleteNotice,
) {
    val report = failure?.let { privacySafeErrorPresentation("CHAT_LOCAL_DELETE", it, detail).report }
    presentText(
        ToastMessage(
            title = AppText.Resource(titleRes),
            detail = detail,
            copyable = report != null,
            diagnosticReport = report,
            localDeleteNotice = notice,
        ),
    )
}

/** Capture the account/runtime fence before offering a user-initiated retry. */
internal fun WhiteNoiseAppState.localDeleteRetryNotice(
    account: String,
    groupId: String,
    isCurrent: () -> Boolean,
    retry: suspend () -> Unit,
): LocalDeleteNotice =
    LocalDeleteNotice(account, setOf(groupId)) {
        launchMutation {
            if (isCurrent()) retry()
        }
    }

/** Only confirmed native absence resolves a deletion warning; client cleanup continues quietly. */
internal fun WhiteNoiseAppState.dismissLocalDeleteFailure(
    account: String,
    groupId: String,
) {
    val current = toast
    val notice = current?.localDeleteNotice
    if (current != null && notice != null && notice.accountRef == account) {
        val remaining = notice.groupIds.filterNot { it.equals(groupId, ignoreCase = true) }.toSet()
        if (remaining.size != notice.groupIds.size) {
            if (remaining.isEmpty()) {
                clearToast(current)
            } else {
                presentText(current.copy(localDeleteNotice = notice.copy(groupIds = remaining)))
            }
        }
    }
}
