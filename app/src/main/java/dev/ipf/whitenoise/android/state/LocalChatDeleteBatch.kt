package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R

/** Shared confirmation lifetime; outcomes distinguish failed deletion from deferred client cleanup. */
internal class LocalChatDeleteObserver(
    val onFailure: (Throwable) -> Unit = {},
    val onCleanupDeferred: () -> Unit = {},
    val readinessBudget: LocalGroupDeleteReadinessBudget = LocalGroupDeleteReadinessBudget(),
    val onNativeCommitted: () -> Unit = {},
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
    notice: LocalDeleteNotice? = null,
) {
    if (result.total <= 1) return
    val detail = AppText.Resource(R.string.chat_list_delete_stopped_detail, listOf(result.deleted, result.total))
    if (notice != null) {
        presentLocalDeleteFailure(R.string.chat_list_delete_stopped, failure, detail, notice)
    } else if (failure != null) {
        presentFailure(R.string.chat_list_delete_stopped, "CHAT_LOCAL_DELETE", failure, detail)
    } else {
        present(R.string.chat_list_delete_stopped, detail)
    }
}

/** An explicit retry retains the originally confirmed targets even after Chats leaves composition. */
internal fun WhiteNoiseAppState.localDeleteBatchRetryNotice(
    controller: ChatsController,
    groupIds: List<String>,
    isCurrent: () -> Boolean,
): LocalDeleteNotice =
    LocalDeleteNotice(requireNotNull(controller.accountRef), groupIds.toSet()) { targets ->
        val wanted = targets.map { it.lowercase() }.toSet()
        val remaining = groupIds.filter { it.lowercase() in wanted }
        launchMutation {
            retryLocalChatDeleteBatch(controller, remaining, isCurrent)
        }
    }

private suspend fun WhiteNoiseAppState.retryLocalChatDeleteBatch(
    controller: ChatsController,
    groupIds: List<String>,
    isCurrent: () -> Boolean,
) {
    if (!isCurrent()) return
    var failure: Throwable? = null
    val observer = LocalChatDeleteObserver(onFailure = { failure = it })
    val result =
        deleteLocalChatsBatch(groupIds, isCurrent) { groupId ->
            controller.deleteGroupLocalFromChatList(groupId, notify = false, observer = observer)
        }
    if (isCurrent() && result.deleted < result.total) {
        presentStoppedLocalChatDeleteBatch(
            result,
            failure,
            notice = localDeleteBatchRetryNotice(controller, groupIds.drop(result.deleted), isCurrent),
        )
    }
}
