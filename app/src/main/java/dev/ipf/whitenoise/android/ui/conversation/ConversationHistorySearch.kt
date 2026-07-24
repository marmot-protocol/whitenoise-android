package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

// One page per FFI round trip — large enough to cross a long history in a few
// dozen calls, small enough to keep each call cheap and cancellation prompt.
internal const val HISTORY_SEARCH_PAGE_SIZE = 200u

// Runaway backstop only, far above any real history at the page size above;
// exhaustiveness is the contract, this guards a cursor that stops advancing.
internal const val HISTORY_SEARCH_MAX_PAGES = 1_000

// Keystroke debounce before an exhaustive scan fires; superseded keystrokes
// cancel the effect (and any in-flight scan) outright.
internal const val HISTORY_SEARCH_DEBOUNCE_MILLIS = 350L

/**
 * Exhaustively searches the conversation's locally stored history for [query]
 * and returns matching message ids oldest-first (the in-chat match list's
 * timeline order). Local-only: the engine query narrows to rows whose stored
 * text contains the needle, and the same body gating the chat-list search uses
 * drops reactions, deletes, and system rows the store query cannot filter.
 * Returns null on a failed page read — callers fall back to the loaded-window
 * matches rather than presenting a partial set as the total.
 */
internal suspend fun searchConversationHistoryMessageIds(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
    query: String,
): List<String>? {
    val account = appState.activeAccountRef
    val needle = query.trim()
    return when {
        account == null -> null
        needle.isEmpty() -> emptyList()
        else -> scanHistoryForNeedle(appState, account, groupIdHex, needle)
    }
}

/** One scanned page reduced to what cursor paging needs: eligible body
 *  matches as (timelineAt, id), the oldest row for the next cursor, and
 *  whether older rows remain. */
internal data class HistoryScanPage(
    val matches: List<Pair<ULong, String>>,
    val oldest: Pair<ULong, String>?,
    val hasMoreBefore: Boolean,
)

internal typealias HistoryPageFetcher = suspend (before: ULong?, beforeMessageId: String?) -> HistoryScanPage?

private suspend fun scanHistoryForNeedle(
    appState: WhiteNoiseAppState,
    account: String,
    groupIdHex: String,
    needle: String,
): List<String>? {
    val ciNeedle = needle.lowercase(Locale.ROOT)
    return paginateHistoryMatches { cursorBefore, cursorMessageId ->
        val page =
            runCatching {
                appState.marmotIo {
                    timelineMessages(
                        account,
                        TimelineMessageQueryFfi(
                            groupIdHex = groupIdHex,
                            search = needle,
                            before = cursorBefore,
                            beforeMessageId = cursorMessageId,
                            after = null,
                            afterMessageId = null,
                            limit = HISTORY_SEARCH_PAGE_SIZE,
                        ),
                    )
                }
            }.getOrElse { throwable ->
                // A cancelled scan must propagate, not resolve to a value the
                // caller could publish over a newer query's results.
                if (throwable is CancellationException) throw throwable
                return@paginateHistoryMatches null
            }
        val matches =
            page.messages
                .filter {
                    ChatListMessageSearch.isSearchableBody(it.kind, it.deleted, it.plaintext) &&
                        ChatListMessageSearch.bodyMatches(it.plaintext, ciNeedle)
                }.map { it.timelineAt to it.messageIdHex }
        val oldest =
            page.messages
                .minWithOrNull(compareBy({ it.timelineAt }, { it.messageIdHex }))
                ?.let { it.timelineAt to it.messageIdHex }
        HistoryScanPage(matches = matches, oldest = oldest, hasMoreBefore = page.hasMoreBefore)
    }
}

/**
 * Cursor-paged accumulation, isolated from the FFI so the paired-cursor
 * contract is unit-testable. [fetchPage] receives the (before, beforeMessageId)
 * pair — both null on the first page, both advancing to the previous page's
 * oldest row thereafter, because the engine rejects one without the other.
 * Returns null when a page read fails (the caller keeps its loaded-window
 * matches); otherwise ids oldest-first.
 */
internal suspend fun paginateHistoryMatches(fetchPage: HistoryPageFetcher): List<String>? {
    val matches = ArrayList<Pair<ULong, String>>()
    var cursorBefore: ULong? = null
    var cursorMessageId: String? = null
    var pages = 0
    var failed = false
    var exhausted = false
    while (!failed && !exhausted && pages < HISTORY_SEARCH_MAX_PAGES) {
        currentCoroutineContext().ensureActive()
        val page = fetchPage(cursorBefore, cursorMessageId)
        if (page == null) {
            failed = true
        } else {
            matches += page.matches
            val oldest = page.oldest
            if (!page.hasMoreBefore || oldest == null || oldest.second == cursorMessageId) {
                exhausted = true
            } else {
                cursorBefore = oldest.first
                cursorMessageId = oldest.second
                pages += 1
            }
        }
    }
    return if (failed) {
        null
    } else {
        matches
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.second }
    }
}
