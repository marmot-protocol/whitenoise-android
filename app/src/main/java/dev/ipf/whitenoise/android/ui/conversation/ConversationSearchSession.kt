package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.snapshotFlow
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.state.TimelineMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Opens one in-chat search session and keeps [bookmark], the reader's position, so its close can return there
 * (#4224). The anchor row of [loadedTimeline] also records the timeline position the close pages back to when a deep
 * search jump evicted that row from the capped window. A shortcut while search is already open keeps the first
 * session's position, and a new session retires a return the previous close left paging.
 */
internal fun ConversationNavigationState.openSearchSession(
    bookmark: ConversationScrollBookmark,
    loadedTimeline: List<TimelineMessage>,
) {
    if (searchOpen) return
    searchJob?.cancel()
    val anchorRow =
        bookmark.anchor.messageId?.let { messageId ->
            loadedTimeline.firstOrNull { it.record.messageIdHex == messageId }
        }
    preSearchScrollAnchor =
        ConversationSearchScrollAnchor(
            bookmark = bookmark,
            match =
                anchorRow?.let {
                    ConversationSearchMatch(
                        messageIdHex = it.record.messageIdHex,
                        timelineAt = it.projected?.timelineAt ?: it.record.recordedAt,
                    )
                },
        )
    searchOpen = true
}

/**
 * Ends the open in-chat search session: [clearSearch] drops its query, pending search navigation is cancelled and
 * [returnTo] runs on [scope] with the position saved at open, after the cancelled navigation has unwound.
 *
 * Only the close that ends an open session does this (#4224). The toolbar's close and system Back can both land
 * for one session, and a repeated close must not cancel the return the first one started. A later search jump
 * still supersedes that return through the shared search job. The return owns a target navigation request, and
 * [returnTo] receives its staleness check: a reader scroll or a newer reply jump ends that request, so the return
 * stops before it pages or scrolls again.
 */
internal fun ConversationNavigationState.closeSearchSession(
    scope: CoroutineScope,
    clearSearch: () -> Unit,
    returnTo: suspend (anchor: ConversationSearchScrollAnchor, isCurrent: () -> Boolean) -> Unit,
) {
    if (!searchOpen) return
    searchOpen = false
    clearSearch()
    searchPinnedMatchId = null
    val previousSearchJob = searchJob
    previousSearchJob?.cancel()
    navigateReplyJob?.cancel()
    val returnRequest = targetNavigation.begin()
    targetHighlight.clear()
    val anchor = preSearchScrollAnchor ?: return
    preSearchScrollAnchor = null
    searchJob =
        scope.launch {
            previousSearchJob?.join()
            returnTo(anchor, returnRequest::isCurrent)
        }
}

/**
 * Pages the window back to the saved position for a search return. A page already in flight, such as an automatic
 * prefetch, makes [loadMatch] report no progress, so the return waits for that page to end and tries again. A
 * message that is really gone still ends after one attempt, because nothing is loading when it fails. Once
 * [stillWanted] turns false, because the reader scrolled while the return waited, no further page is requested.
 */
internal suspend fun pageBackForSearchReturn(
    isLoadingPage: () -> Boolean,
    stillWanted: () -> Boolean,
    loadMatch: suspend () -> Boolean,
): Boolean {
    var attempts = 0
    var available = false
    do {
        snapshotFlow(isLoadingPage).first { !it }
        if (!stillWanted()) break
        available = loadMatch()
        attempts += 1
    } while (!available && isLoadingPage() && attempts < SEARCH_RETURN_PAGE_ATTEMPTS)
    return available
}

/** Bounds the waits behind other pages, so a reader whose prefetch keeps restarting is not chased forever. */
private const val SEARCH_RETURN_PAGE_ATTEMPTS = 3
