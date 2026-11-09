package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.state.ChatFolderQuery
import dev.ipf.whitenoise.android.state.ChatListSelectAllScope
import kotlinx.coroutines.delay
import java.util.Locale

/** Bounded retries while MDK's chat presentation warms up; it never returns a partial list (#2925). */
internal const val CHAT_LIST_SELECT_ALL_ATTEMPTS = 5
internal const val CHAT_LIST_SELECT_ALL_RETRY_MS = 250L

/**
 * The native scope whose complete membership Select all may claim (#2925, #3652), or null where Android
 * still filters loaded windows: search, unread and participant folders keep loaded-window semantics.
 * The Archived system folder reads its view while its rule and membership are untouched, and any other
 * folder with a native query reads that query.
 */
internal fun chatListCompleteSelectAllScope(
    folderSelected: Boolean,
    untouchedArchivedFolder: Boolean,
    unreadOnly: Boolean,
    searchOpen: Boolean,
    folderQuery: ChatFolderQuery? = null,
): ChatListSelectAllScope? =
    when {
        searchOpen || unreadOnly -> null
        untouchedArchivedFolder -> ChatListSelectAllScope(ChatListViewFfi.ARCHIVED)
        folderSelected -> folderQuery?.let(ChatListSelectAllScope::of)
        else -> ChatListSelectAllScope(ChatListViewFfi.CHATS)
    }

/**
 * Select-all intent bound to one native scope. [resolved] stays false until MDK returns the
 * complete set, so the bar never presents a partial count or bulk action as complete.
 */
internal data class ChatListCompleteSelection(
    val scope: ChatListSelectAllScope,
    val resolved: Boolean,
) {
    /** The view the selection's bulk actions behave like. */
    val view: ChatListViewFfi get() = scope.view
}

/** Reads the complete view, retrying only the not-ready refusal; other failures surface to the caller. */
internal suspend fun resolveCompleteChatListIds(
    attempts: Int = CHAT_LIST_SELECT_ALL_ATTEMPTS,
    retryDelayMs: Long = CHAT_LIST_SELECT_ALL_RETRY_MS,
    pause: suspend (Long) -> Unit = { delay(it) },
    read: suspend () -> List<String>?,
): List<String>? {
    require(attempts > 0)
    var attempt = 0
    while (true) {
        try {
            return read()
        } catch (notReady: MarmotKitException.ChatPresentationNotReady) {
            attempt += 1
            if (attempt >= attempts) throw notReady
            pause(retryDelayMs * attempt)
        }
    }
}

/**
 * Selection kept across window movement: a resolved complete selection keeps unloaded IDs and is
 * pruned only by native revalidation; any other selection follows the loaded filtered rows.
 */
internal fun reconcileChatListSelection(
    selected: Set<String>,
    visibleIds: Set<String>,
    complete: ChatListCompleteSelection? = null,
): Set<String> = if (complete != null) selected else selected.intersect(visibleIds)

/**
 * Actions offered for a complete selection. Loaded rows supply advisory hints; unloaded rows are
 * unknown, so both directions stay available and every command rechecks natively per chat.
 */
internal fun chatListCompleteSelectionActions(
    loaded: ChatListSelectionActions,
    complete: ChatListCompleteSelection,
    allLoaded: Boolean,
): ChatListSelectionActions =
    if (allLoaded) {
        loaded
    } else {
        ChatListSelectionActions(
            showMarkRead = true,
            showMarkUnread = true,
            showMute = true,
            showUnmute = true,
            showPin = complete.view == ChatListViewFfi.CHATS,
            showUnpin = complete.view == ChatListViewFfi.CHATS,
            showMovePinned = false,
            archiveAction =
                if (complete.view == ChatListViewFfi.ARCHIVED) {
                    ChatListBulkArchiveAction.Unarchive
                } else {
                    ChatListBulkArchiveAction.Archive
                },
            showAlternateArchive = false,
        )
    }

/**
 * The selection after a complete read: every native ID, spelled as its loaded row when present so
 * loaded-row checks keep matching, otherwise in canonical lowercase form.
 */
internal fun mergeCompleteChatListIds(
    nativeIds: List<String>,
    visibleIds: Set<String>,
): Set<String> {
    val visibleByCanonical = visibleIds.associateBy { it.lowercase(Locale.ROOT) }
    return nativeIds.mapTo(linkedSetOf()) { id ->
        val canonical = id.lowercase(Locale.ROOT)
        visibleByCanonical[canonical] ?: canonical
    }
}

/** Selected IDs that native revalidation still places in the view, compared case-insensitively. */
internal fun retainRevalidatedChatListIds(
    selected: Set<String>,
    revalidated: List<String>,
): Set<String> {
    val kept = revalidated.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
    return selected.filterTo(linkedSetOf()) { it.lowercase(Locale.ROOT) in kept }
}
