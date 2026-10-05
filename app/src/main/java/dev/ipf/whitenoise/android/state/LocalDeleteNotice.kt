package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R

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
): LocalDeleteNotice = LocalDeleteNotice(account, setOf(groupId)) {
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
