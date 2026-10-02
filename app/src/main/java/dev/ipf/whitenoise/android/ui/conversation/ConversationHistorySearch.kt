package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isRetentionExpiredForSearch
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
 * Whether the in-chat match set is authoritative (#2873): still loading the
 * full local history, complete, or limited to the loaded window after a
 * failed scan. Only a complete or explicitly loaded-only set may be stepped.
 */
internal enum class ConversationSearchScanStatus {
    IDLE,
    LOADING,
    COMPLETE,
    FAILED,
}

/** Derives the scan status from the query and the scan outcome published for it. */
internal fun conversationSearchScanStatus(
    query: String,
    scanMatches: List<ConversationSearchMatch>?,
    scanFailed: Boolean,
): ConversationSearchScanStatus =
    when {
        query.isBlank() -> ConversationSearchScanStatus.IDLE
        scanMatches != null -> ConversationSearchScanStatus.COMPLETE
        scanFailed -> ConversationSearchScanStatus.FAILED
        else -> ConversationSearchScanStatus.LOADING
    }

/** Arrows never step a partial set while the full-history scan is still loading. */
internal fun ConversationSearchScanStatus.allowsSearchSteps(matchCount: Int): Boolean =
    matchCount > 0 && (this == ConversationSearchScanStatus.COMPLETE || this == ConversationSearchScanStatus.FAILED)

/**
 * Exhaustively searches the conversation's locally stored history for [query]
 * and returns matching message ids oldest-first (the in-chat match list's
 * timeline order). Local-only: the engine query narrows to rows whose stored
 * text contains the needle, and the same body gating the chat-list search uses
 * drops reactions, deletes, and system rows the store query cannot filter.
 * Returns null on a failed page read — callers fall back to the loaded-window
 * matches rather than presenting a partial set as the total. [accountRef] is the
 * conversation's bound account, so a notification-opened conversation whose
 * account switch has not landed yet still searches its own store (#2873).
 */
internal suspend fun searchConversationHistoryMatches(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    groupIdHex: String,
    query: String,
    readPage: HistoryPageReader = { account, pageQuery ->
        appState.marmotIo { timelineMessages(account, pageQuery) }
    },
): List<ConversationSearchMatch>? {
    val needle = query.trim()
    return when {
        accountRef == null -> null
        needle.isEmpty() -> emptyList()
        else -> scanHistoryForNeedle(accountRef, groupIdHex, needle, readPage)
    }
}

/** Reads one store page for the conversation's own account. */
internal typealias HistoryPageReader = suspend (account: String, query: TimelineMessageQueryFfi) -> TimelinePageFfi

/** One scanned page reduced to what cursor paging needs: eligible body
 *  matches as (timelineAt, id), the oldest row for the next cursor, and
 *  whether older rows remain. */
internal data class HistoryScanPage(
    val matches: List<Pair<ULong, String>>,
    val oldest: Pair<ULong, String>?,
    val hasMoreBefore: Boolean,
)

internal typealias HistoryPageFetcher = suspend (before: ULong?, beforeMessageId: String?) -> HistoryScanPage?

/** Pages backward through one account's stored history for [needle]. */
private suspend fun scanHistoryForNeedle(
    account: String,
    groupIdHex: String,
    needle: String,
    readPage: HistoryPageReader,
): List<ConversationSearchMatch>? {
    val ciNeedle = needle.lowercase(Locale.ROOT)
    return paginateHistoryMatches { cursorBefore, cursorMessageId ->
        val page =
            runCatching {
                readPage(
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
            }.getOrElse { throwable ->
                // A cancelled scan must propagate, not resolve to a value the
                // caller could publish over a newer query's results.
                if (throwable is CancellationException) throw throwable
                return@paginateHistoryMatches null
            }
        val nowMillis = System.currentTimeMillis()
        val matches =
            page.messages
                .filter {
                    !isRetentionExpiredForSearch(it, nowMillis) &&
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
 * Returns null when a page read fails or the runaway cap is reached before
 * exhaustion (the caller keeps its loaded-window matches); otherwise ids
 * oldest-first.
 */
internal suspend fun paginateHistoryMatches(
    maxPages: Int = HISTORY_SEARCH_MAX_PAGES,
    fetchPage: HistoryPageFetcher,
): List<ConversationSearchMatch>? {
    val matches = ArrayList<Pair<ULong, String>>()
    var cursorBefore: ULong? = null
    var cursorMessageId: String? = null
    var pages = 0
    var failed = false
    var exhausted = false
    while (!failed && !exhausted && pages < maxPages) {
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
    return if (failed || !exhausted) {
        null
    } else {
        matches
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (timelineAt, messageIdHex) ->
                ConversationSearchMatch(messageIdHex = messageIdHex, timelineAt = timelineAt)
            }
    }
}
