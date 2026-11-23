package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recent destinations on the blank search page (#4004): order, identity, bounds and scope. */
class GlobalSearchRecentsTest {
    private val recents = GlobalSearchRecents(TOKEN)

    @Test
    fun opensListNewestFirstAndOnceEach() {
        recents.record(TOKEN, chat("aa"))
        recents.record(TOKEN, profile("npub1alice"))
        recents.record(TOKEN, chat("bb"))
        recents.record(TOKEN, chat("AA"))
        recents.record(TOKEN, profile("NPUB1ALICE"))

        assertEquals(listOf("profile:npub1alice", "chat:aa", "chat:bb"), recents.entries.map { it.key })
    }

    @Test
    fun listKeepsOnlyTheNewestUpToItsLimit() {
        repeat(GLOBAL_SEARCH_RECENT_LIMIT + 3) { recents.record(TOKEN, chat("%02x".format(it))) }

        assertEquals(GLOBAL_SEARCH_RECENT_LIMIT, recents.entries.size)
        assertEquals(chat("%02x".format(GLOBAL_SEARCH_RECENT_LIMIT + 2)), recents.entries.first())
        assertFalse(chat("00") in recents.entries)
    }

    @Test
    fun removeDropsOneEntryAndClearEmptiesTheList() {
        recents.record(TOKEN, chat("aa"))
        recents.record(TOKEN, profile("npub1alice"))
        recents.record(TOKEN, chat("bb"))

        recents.remove(profile("npub1alice"))
        assertEquals(listOf(chat("bb"), chat("aa")), recents.entries)

        recents.clear()
        assertTrue(recents.entries.isEmpty())
    }

    /** An open captured for one account or runtime never lands in another one's list. */
    @Test
    fun openFromAnotherScopeIsRefused() {
        val other = GlobalSearchAccountScope.from("work", 1).encodeToken()

        assertFalse(recents.record(other, chat("aa")))
        assertTrue(recents.entries.isEmpty())
        assertTrue(recents.record(TOKEN, chat("aa")))
    }

    @Test
    fun listShowsOnlyOnTheBlankUnfilteredSearchPage() {
        val blank = GlobalSearchState(isOpen = true, accountScopeToken = TOKEN)

        assertTrue(globalSearchRecentsVisible(blank))
        assertTrue(globalSearchRecentsVisible(blank.copy(query = "   ")))
        assertFalse(globalSearchRecentsVisible(blank.copy(isOpen = false)))
        assertFalse(globalSearchRecentsVisible(blank.copy(query = "alice")))
        assertFalse(globalSearchRecentsVisible(blank.copy(folderFilters = setOf("saved"))))
        assertFalse(globalSearchRecentsVisible(blank.copy(chatTypeFilters = setOf(GlobalSearchChatType.DIRECT))))
        assertFalse(
            globalSearchRecentsVisible(blank.copy(dateFilterSelection = GlobalSearchDateFilterSelection.Today)),
        )
    }

    /** Unreadable chats and undecodable profiles are left out; the rest keep their order. */
    @Test
    fun resolutionOmitsUnavailableDestinations() {
        val entries = listOf(profile("npub1alice"), chat("gone"), profile("npub1broken"))

        val shown = resolveGlobalSearchRecents(entries, emptyMap()) { npub -> "a1".takeIf { npub == "npub1alice" } }

        assertEquals(
            listOf(GlobalSearchRecentDestination.Profile(profile("npub1alice"), "a1")),
            shown,
        )
    }

    private fun chat(id: String) = GlobalSearchRecent.Chat(id)

    private fun profile(npub: String) = GlobalSearchRecent.Profile(npub)

    private companion object {
        val TOKEN = GlobalSearchAccountScope.from("personal", 1).encodeToken()
    }
}
