package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.ChatListItem
import java.util.Locale

/** Most destinations the blank search page offers again (#4004). */
internal const val GLOBAL_SEARCH_RECENT_LIMIT = 8

/** A chat or profile opened from global search, held only by its stable ID. */
internal sealed interface GlobalSearchRecent {
    /** Identity across both kinds, so one destination appears once. */
    val key: String

    data class Chat(
        val groupIdHex: String,
    ) : GlobalSearchRecent {
        override val key: String = "chat:" + groupIdHex.lowercase(Locale.ROOT)
    }

    data class Profile(
        val npub: String,
    ) : GlobalSearchRecent {
        override val key: String = "profile:" + npub.lowercase(Locale.ROOT)
    }
}

/** [entry] first, its older copy dropped and the list cut to [GLOBAL_SEARCH_RECENT_LIMIT]. */
internal fun List<GlobalSearchRecent>.recording(entry: GlobalSearchRecent): List<GlobalSearchRecent> =
    (listOf(entry) + filterNot { it.key == entry.key }).take(GLOBAL_SEARCH_RECENT_LIMIT)

/**
 * Recently opened search destinations for one account session. The shell keeps one per account scope in
 * memory only: another account starts empty and process restart forgets the list. An open captured under
 * a different scope token is refused, so a late result never lands in another account's list.
 */
internal class GlobalSearchRecents(
    val accountScopeToken: String,
) {
    var entries: List<GlobalSearchRecent> by mutableStateOf(emptyList())
        private set

    fun record(
        scopeToken: String,
        entry: GlobalSearchRecent,
    ): Boolean {
        if (scopeToken != accountScopeToken) return false
        entries = entries.recording(entry)
        return true
    }

    fun remove(entry: GlobalSearchRecent) {
        entries = entries.filterNot { it.key == entry.key }
    }

    fun clear() {
        entries = emptyList()
    }
}

/**
 * Holds the current account scope's [GlobalSearchRecents] beyond one composition, so Activity recreation in the
 * same process keeps them. Another scope replaces the held list; nothing is written to saved state.
 */
internal class GlobalSearchRecentsOwner {
    private var held: GlobalSearchRecents? = null

    fun obtain(scopeToken: String): GlobalSearchRecents =
        held?.takeIf { it.accountScopeToken == scopeToken } ?: GlobalSearchRecents(scopeToken).also { held = it }
}

/** The list replaces nothing: it shows only on the blank search page, never beside a query, filter or library. */
internal fun globalSearchRecentsVisible(state: GlobalSearchState): Boolean {
    val blankQuery = state.query.isBlank()
    return state.isOpen && blankQuery && !state.hasActiveFilters
}

/** A recent destination ready to draw; chats resolve to their current row, profiles to their key. */
internal sealed interface GlobalSearchRecentDestination {
    val entry: GlobalSearchRecent

    data class Chat(
        override val entry: GlobalSearchRecent.Chat,
        val item: ChatListItem,
    ) : GlobalSearchRecentDestination

    data class Profile(
        override val entry: GlobalSearchRecent.Profile,
        val accountIdHex: String,
    ) : GlobalSearchRecentDestination
}

/** Recent entries in order, omitting chats no longer readable and profiles whose key no longer decodes. */
internal fun resolveGlobalSearchRecents(
    entries: List<GlobalSearchRecent>,
    chats: Map<String, ChatListItem>,
    profileAccount: (String) -> String?,
): List<GlobalSearchRecentDestination> =
    entries.mapNotNull { entry ->
        when (entry) {
            is GlobalSearchRecent.Chat ->
                chats[entry.groupIdHex.lowercase(Locale.ROOT)]?.let { GlobalSearchRecentDestination.Chat(entry, it) }
            is GlobalSearchRecent.Profile ->
                profileAccount(entry.npub)?.let { GlobalSearchRecentDestination.Profile(entry, it) }
        }
    }
