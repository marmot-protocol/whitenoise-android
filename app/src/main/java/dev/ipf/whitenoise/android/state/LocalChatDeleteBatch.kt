package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R

/** Shared confirmation lifetime; outcomes distinguish failed deletion from deferred client cleanup. */
internal class LocalChatDeleteObserver(
    val onFailure: (Throwable) -> Unit = {},
    val onCleanupDeferred: () -> Unit = {},
    val readinessBudget: LocalGroupDeleteReadinessBudget = LocalGroupDeleteReadinessBudget(),
)

internal data class LocalChatDeleteBatchResult(
    val total: Int,
    val attempted: Int,
    val deleted: Int,
)

/** One failure stops the batch. Neither uncertainty nor a changed owner admits another mutation. */
internal suspend fun deleteLocalChatsBatch(
    groupIds: Collection<String>,
    isCurrent: () -> Boolean,
    delete: suspend (String) -> Boolean,
): LocalChatDeleteBatchResult {
    val ids = groupIds.distinctBy { it.lowercase() }
    var attempted = 0
    var deleted = 0
    var canContinue = true
    for (id in ids) {
        if (!canContinue || !isCurrent()) break
        attempted++
        canContinue = delete(id)
        if (canContinue) deleted++
    }
    return LocalChatDeleteBatchResult(ids.size, attempted, deleted)
}

/** Keep a stopped batch actionable, with the exact failing attempt's diagnostic rather than a success banner. */
internal fun WhiteNoiseAppState.presentStoppedLocalChatDeleteBatch(
    result: LocalChatDeleteBatchResult,
    failure: Throwable?,
) {
    val detail = AppText.Resource(R.string.chat_list_delete_stopped_detail, listOf(result.deleted, result.total))
    if (failure != null) {
        presentFailure(R.string.chat_list_delete_stopped, "CHAT_LOCAL_DELETE", failure, detail)
    } else {
        present(R.string.chat_list_delete_stopped, detail)
    }
}
