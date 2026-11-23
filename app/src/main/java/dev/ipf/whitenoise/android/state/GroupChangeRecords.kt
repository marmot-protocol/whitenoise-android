package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.ui.chats.resolveCompleteChatListIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes MDK's retained record of the change [notice] reported, now that the user has seen it, so a later stream open
 * does not raise it again. A queued send's record, which may not exist, is found by its intent. A failed read or delete
 * leaves the record for the next open.
 */
internal suspend fun MarmotInterface.acknowledgeGroupChange(notice: GroupChangeNotice) {
    val commits =
        notice.commitIdHex?.let { listOf(it) }
            ?: notice.queuedIntentIdHex
                ?.let { intent ->
                    groupChangesNotDelivered(notice.accountRef, notice.groupIdHex)
                        .filter { it.queuedIntentIdHex.equals(intent, ignoreCase = true) }
                        .map { it.commitIdHex }
                }.orEmpty()
    commits.forEach { acknowledgeChangeNotDelivered(notice.accountRef, notice.groupIdHex, it) }
}

/**
 * The newest change of [accountRef]'s that MDK gave up and still holds, from every listed and archived chat, skipping
 * MDK's own upkeep. MDK announces each one only once but keeps its record until it is acknowledged, so a change given
 * up while no stream listened is read here. A failed read skips that view or chat.
 */
internal suspend fun MarmotInterface.newestRetainedGroupChange(accountRef: String): GroupChangeNotice? {
    val groups =
        listOf(ChatListViewFfi.CHATS, ChatListViewFfi.ARCHIVED)
            .flatMap { view -> listedChats(accountRef, view) }
            .distinct()
    return groups
        .flatMap { group ->
            runCatchingCancellable { groupChangesNotDelivered(accountRef, group) }.getOrNull().orEmpty()
        }.sortedByDescending { it.at }
        .firstNotNullOfOrNull { retainedGroupChangeNotice(accountRef, it) }
}

/**
 * [view]'s chats for [accountRef]. While MDK backfills the chat list it refuses the read rather than return part of it,
 * and this read runs once per stream open, so that refusal is retried for about a minute before the view is skipped.
 */
private suspend fun MarmotInterface.listedChats(
    accountRef: String,
    view: ChatListViewFfi,
): List<String> =
    runCatchingCancellable {
        resolveCompleteChatListIds(RETAINED_CHANGE_LIST_ATTEMPTS, RETAINED_CHANGE_LIST_RETRY_MS) {
            chatListIds(accountRef, view)
        }
    }.getOrNull().orEmpty()

/**
 * Raises the active account's newest retained change when the event stream opens and acknowledges it once the user has
 * seen it. A toast shows one notice at a time, so any older change waits for a later open.
 */
internal suspend fun WhiteNoiseAppState.presentRetainedGroupChange(marmot: MarmotInterface) {
    val account = withContext(Dispatchers.Main) { activeAccountRef } ?: return
    val notice = marmot.newestRetainedGroupChange(account) ?: return
    if (withContext(Dispatchers.Main) { showGroupChangeNotice(notice) }) {
        runCatchingCancellable { marmot.acknowledgeGroupChange(notice) }
    }
}

/** Reads of a not-ready chat list at stream open, each waiting 0.5 s longer than the last: about 52 s in all. */
private const val RETAINED_CHANGE_LIST_ATTEMPTS = 15
private const val RETAINED_CHANGE_LIST_RETRY_MS = 500L
