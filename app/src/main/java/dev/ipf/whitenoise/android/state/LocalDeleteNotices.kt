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
    toast = ToastMessage(
        title = AppText.Resource(titleRes),
        detail = detail,
        copyable = report != null,
        diagnosticReport = report,
        localDeleteNotice = notice,
    )
}

/** Only confirmed native absence resolves a deletion warning; client cleanup continues quietly. */
internal fun WhiteNoiseAppState.dismissLocalDeleteFailure(
    account: String,
    groupId: String,
) {
    val current = toast ?: return
    val notice = current.localDeleteNotice ?: return
    if (notice.accountRef != account) return
    val remaining = notice.groupIds.filterNot { it.equals(groupId, ignoreCase = true) }.toSet()
    if (remaining.size == notice.groupIds.size) return
    if (remaining.isEmpty()) {
        clearToast(current)
    } else {
        toast = current.copy(localDeleteNotice = notice.copy(groupIds = remaining))
    }
}
