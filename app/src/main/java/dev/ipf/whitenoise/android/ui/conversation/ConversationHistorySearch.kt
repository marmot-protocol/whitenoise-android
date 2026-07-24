package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.core.ChatListMessageSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
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

private suspend fun scanHistoryForNeedle(
    appState: WhiteNoiseAppState,
    account: String,
    groupIdHex: String,
    needle: String,
): List<String>? {
    val ciNeedle = needle.lowercase(Locale.ROOT)
    val matches = ArrayList<Pair<ULong, String>>()
    var beforeMessageId: String? = null
    var pages = 0
    var failed = false
    var exhausted = false
    while (!failed && !exhausted && pages < HISTORY_SEARCH_MAX_PAGES) {
        currentCoroutineContext().ensureActive()
        val page =
            runCatching {
                appState.marmotIo {
                    timelineMessages(
                        account,
                        TimelineMessageQueryFfi(
                            groupIdHex = groupIdHex,
                            search = needle,
                            before = null,
                            beforeMessageId = beforeMessageId,
                            after = null,
                            afterMessageId = null,
                            limit = HISTORY_SEARCH_PAGE_SIZE,
                        ),
                    )
                }
            }.getOrNull()
        if (page == null) {
            failed = true
        } else {
            for (record in page.messages) {
                if (ChatListMessageSearch.isSearchableBody(record.kind, record.deleted, record.plaintext) &&
                    ChatListMessageSearch.bodyMatches(record.plaintext, ciNeedle)
                ) {
                    matches += record.timelineAt to record.messageIdHex
                }
            }
            // Cursor to the oldest row in this page so the next query returns
            // strictly older needle hits — order-agnostic, same as the
            // chat-list search's paging. A non-advancing cursor means done.
            val cursor =
                page.messages
                    .minWithOrNull(compareBy({ it.timelineAt }, { it.messageIdHex }))
                    ?.messageIdHex
            if (!page.hasMoreBefore || cursor == null || cursor == beforeMessageId) {
                exhausted = true
            } else {
                beforeMessageId = cursor
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
