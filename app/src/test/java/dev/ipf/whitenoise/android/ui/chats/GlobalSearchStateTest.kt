package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchStateTest {
    private fun dateFilter(
        stableId: String,
        label: String,
    ) = GlobalSearchDateFilter(stableId, label)

    private fun contentFilter(
        stableId: String,
        label: String,
    ) = GlobalSearchContentFilter(stableId, label)

    private fun accountScope(
        accountRef: String?,
        runtimeGeneration: Int,
    ) = GlobalSearchAccountScope(accountRef, runtimeGeneration)

    @Test
    fun openSearchSetsIsOpen() {
        val opened = GlobalSearchTransitions.openSearch(GlobalSearchState())
        assertTrue(opened.isOpen)
    }

    @Test
    fun closeSearchResetsTransientUi() {
        val scopeToken = accountScope("personal", 2).encodeToken()
        val state =
            GlobalSearchState(
                isOpen = true,
                query = "hello",
                filterSheetOpen = true,
                accountScopeToken = scopeToken,
                chatFilters = setOf(GlobalSearchChatFilter("abc", "Chat A")),
                senderFilters = setOf(GlobalSearchSenderFilter("npub1", "Sender B")),
                dateFilters = setOf(dateFilter("today", "Today")),
                contentFilters = setOf(contentFilter("images", "Images")),
            )
        val closed = GlobalSearchTransitions.closeSearch(state)
        assertFalse(closed.isOpen)
        assertEquals("", closed.query)
        assertFalse(closed.filterSheetOpen)
        assertEquals(scopeToken, closed.accountScopeToken)
        assertTrue(closed.chatFilters.isEmpty())
        assertTrue(closed.senderFilters.isEmpty())
        assertTrue(closed.dateFilters.isEmpty())
        assertTrue(closed.contentFilters.isEmpty())
    }

    @Test
    fun setQueryUpdatesTrimmedValue() {
        val updated = GlobalSearchTransitions.setQuery(GlobalSearchState(isOpen = true), "  hello  ")
        assertEquals("  hello  ", updated.query)
    }

    @Test
    fun filterSheetOpenAndDismiss() {
        val open = GlobalSearchTransitions.openFilterSheet(GlobalSearchState(isOpen = true))
        assertTrue(open.filterSheetOpen)
        val dismissed = GlobalSearchTransitions.dismissFilterSheet(open)
        assertFalse(dismissed.filterSheetOpen)
    }

    @Test
    fun applyAndRemoveFilters() {
        val chat = GlobalSearchChatFilter("group1", "Chat A")
        val sender = GlobalSearchSenderFilter("npub1", "Sender B")
        val date = dateFilter("last-7-days", "Last 7 days")
        val content = contentFilter("links", "Links")
        var state = GlobalSearchState(isOpen = true)
        state = GlobalSearchTransitions.applyChatFilter(state, chat)
        state = GlobalSearchTransitions.applySenderFilter(state, sender)
        state = GlobalSearchTransitions.applyDateFilter(state, date)
        state = GlobalSearchTransitions.applyContentFilter(state, content)
        assertEquals(setOf(chat), state.chatFilters)
        assertEquals(setOf(sender), state.senderFilters)
        assertEquals(setOf(date), state.dateFilters)
        assertEquals(setOf(content), state.contentFilters)
        state = GlobalSearchTransitions.removeFilter(state, chat.chipId)
        state = GlobalSearchTransitions.removeFilter(state, sender.chipId)
        assertTrue(state.chatFilters.isEmpty())
        assertTrue(state.senderFilters.isEmpty())
    }

    @Test
    fun applyingSameStableIdReplacesItsDisplayLabel() {
        val original = GlobalSearchChatFilter("group1", "Old label")
        val renamed = GlobalSearchChatFilter("group1", "New label")
        val state =
            GlobalSearchTransitions.applyChatFilter(
                GlobalSearchTransitions.applyChatFilter(GlobalSearchState(), original),
                renamed,
            )

        assertEquals(setOf(renamed), state.chatFilters)
        assertEquals(listOf("chat:group1"), GlobalSearchActiveChips.from(state).items.map { it.chipId })
    }

    @Test
    fun clearAllFiltersKeepsSearchOpenAndQuery() {
        val state =
            GlobalSearchState(
                isOpen = true,
                query = "needle",
                chatFilters = setOf(GlobalSearchChatFilter("g1", "A")),
                dateFilters = setOf(dateFilter("today", "Today")),
            )
        val cleared = GlobalSearchTransitions.clearAllFilters(state)
        assertTrue(cleared.isOpen)
        assertEquals("needle", cleared.query)
        assertTrue(cleared.chatFilters.isEmpty())
        assertTrue(cleared.dateFilters.isEmpty())
    }

    @Test
    fun queryAlgebraAndAcrossCategoriesOrWithin() {
        val state =
            GlobalSearchState(
                isOpen = true,
                query = "needle",
                chatFilters =
                    setOf(
                        GlobalSearchChatFilter("g1", "A"),
                        GlobalSearchChatFilter("g2", "B"),
                    ),
                senderFilters = setOf(GlobalSearchSenderFilter("npub1", "Bob")),
                dateFilters = setOf(dateFilter("today", "Today"), dateFilter("last-7-days", "Last 7 days")),
                contentFilters = setOf(contentFilter("text", "Text")),
            )
        val algebra = GlobalSearchQueryAlgebra.from(state)
        assertEquals(4, algebra.activeCategoryCount)
        assertTrue(algebra.matchesCategory(GlobalSearchFilterCategory.Chat, "g1"))
        assertTrue(algebra.matchesCategory(GlobalSearchFilterCategory.Chat, "g2"))
        assertTrue(algebra.matchesCategory(GlobalSearchFilterCategory.Sender, "npub1"))
        assertTrue(algebra.matchesCategory(GlobalSearchFilterCategory.Date, "today"))
        assertFalse(algebra.matchesCategory(GlobalSearchFilterCategory.Chat, "g3"))
    }

    @Test
    fun queryAlgebraRequiresEveryActiveCategoryAndNonblankText() {
        val state =
            GlobalSearchState(
                isOpen = true,
                query = "needle",
                chatFilters = setOf(GlobalSearchChatFilter("g1", "A")),
                senderFilters = setOf(GlobalSearchSenderFilter("npub1", "Bob")),
                dateFilters = setOf(dateFilter("today", "Today")),
                contentFilters = setOf(contentFilter("text", "Text")),
            )
        val algebra = GlobalSearchQueryAlgebra.from(state)
        val fullMatch =
            GlobalSearchCandidate(
                textMatches = true,
                chatId = "g1",
                senderId = "npub1",
                dateId = "today",
                contentIds = setOf("text", "link"),
            )
        val missingCategory =
            GlobalSearchCandidate(
                textMatches = true,
                chatId = "g1",
                senderId = "npub1",
                dateId = "today",
                contentIds = emptySet(),
            )
        val textMismatch =
            GlobalSearchCandidate(
                textMatches = false,
                chatId = "g1",
                senderId = "npub1",
                dateId = "today",
                contentIds = setOf("text"),
            )
        assertTrue(algebra.matches(fullMatch))
        assertFalse(algebra.matches(missingCategory))
        assertFalse(algebra.matches(textMismatch))
    }

    @Test
    fun queryAlgebraOrWithinCategoryAndIgnoresEmptyCategories() {
        val state =
            GlobalSearchState(
                isOpen = true,
                chatFilters =
                    setOf(
                        GlobalSearchChatFilter("g1", "A"),
                        GlobalSearchChatFilter("g2", "B"),
                    ),
            )
        val algebra = GlobalSearchQueryAlgebra.from(state)
        assertTrue(
            algebra.matches(
                GlobalSearchCandidate(chatId = "g2"),
            ),
        )
        assertFalse(
            algebra.matches(
                GlobalSearchCandidate(chatId = "g3"),
            ),
        )
    }

    @Test
    fun activeChipsHaveStableIdentityAndCount() {
        val state =
            GlobalSearchState(
                isOpen = true,
                chatFilters = setOf(GlobalSearchChatFilter("g1", "Alice")),
                contentFilters =
                    setOf(
                        contentFilter("files", "Files"),
                        contentFilter("links", "Links"),
                    ),
            )
        val chips = GlobalSearchActiveChips.from(state)
        assertEquals(3, chips.count)
        assertEquals(
            listOf("chat:g1", "content:files", "content:links"),
            chips.items.map { it.chipId },
        )
    }

    @Test
    fun activeChipOrderIsStableAcrossSelectionInsertionOrder() {
        val first = GlobalSearchContentFilter("a", "First")
        val second = GlobalSearchContentFilter("b", "Second")
        val forward = GlobalSearchState(contentFilters = linkedSetOf(first, second))
        val reverse = GlobalSearchState(contentFilters = linkedSetOf(second, first))

        assertEquals(GlobalSearchActiveChips.from(forward), GlobalSearchActiveChips.from(reverse))
    }

    @Test
    fun reconcileAccountScopePreservesScopedFiltersWhenGenerationUnchanged() {
        val scope = accountScope("personal", 4)
        val state =
            GlobalSearchState(
                isOpen = true,
                query = "keep",
                accountScopeToken = scope.encodeToken(),
                chatFilters = setOf(GlobalSearchChatFilter("g1", "Alice")),
                senderFilters = setOf(GlobalSearchSenderFilter("npub1", "Bob")),
                dateFilters = setOf(dateFilter("today", "Today")),
                contentFilters = setOf(contentFilter("text", "Text")),
            )
        val reconciled = GlobalSearchTransitions.reconcileAccountScope(state, scope)
        assertEquals(state, reconciled)
    }

    @Test
    fun reconcileAccountScopeClearsScopedFiltersWhenGenerationChanges() {
        val previousScope = accountScope("personal", 1)
        val nextScope = accountScope("personal", 2)
        val state =
            GlobalSearchState(
                isOpen = true,
                query = "keep",
                accountScopeToken = previousScope.encodeToken(),
                chatFilters = setOf(GlobalSearchChatFilter("g1", "Alice")),
                senderFilters = setOf(GlobalSearchSenderFilter("npub1", "Bob")),
                dateFilters = setOf(dateFilter("today", "Today")),
                contentFilters = setOf(contentFilter("text", "Text")),
            )
        val reconciled = GlobalSearchTransitions.reconcileAccountScope(state, nextScope)
        assertEquals(nextScope.encodeToken(), reconciled.accountScopeToken)
        assertEquals("keep", reconciled.query)
        assertTrue(reconciled.isOpen)
        assertTrue(reconciled.chatFilters.isEmpty())
        assertTrue(reconciled.senderFilters.isEmpty())
        assertEquals(setOf(dateFilter("today", "Today")), reconciled.dateFilters)
        assertEquals(setOf(contentFilter("text", "Text")), reconciled.contentFilters)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val scopeToken = accountScope("personal", 3).encodeToken()
        val state =
            GlobalSearchState(
                isOpen = true,
                query = "hello\u001fworld \uD83D\uDE00 \u0627\u0644\u0639\u0631\u0628\u064A\u0629",
                filterSheetOpen = true,
                accountScopeToken = scopeToken,
                chatFilters = setOf(GlobalSearchChatFilter("group\u001e1\u001dpart", "Alice\u001f\u001dlabel")),
                senderFilters = setOf(GlobalSearchSenderFilter("npub1", "Bob")),
                dateFilters = setOf(dateFilter("last-30-days", "Last 30 days")),
                contentFilters = setOf(contentFilter("videos", "Videos")),
            )
        val encoded = encodeGlobalSearchState(state)
        val decoded = decodeGlobalSearchState(encoded)
        assertEquals(state, decoded)
    }

    @Test
    fun encodeIsDeterministicAcrossInsertionOrder() {
        val chatA = GlobalSearchChatFilter("g1", "A")
        val chatB = GlobalSearchChatFilter("g2", "B")
        val dateA = dateFilter("today", "Today")
        val dateB = dateFilter("last-7-days", "Last 7 days")
        val forward =
            GlobalSearchState(
                isOpen = true,
                chatFilters = linkedSetOf(chatA, chatB),
                dateFilters = linkedSetOf(dateA, dateB),
            )
        val reverse =
            GlobalSearchState(
                isOpen = true,
                chatFilters = linkedSetOf(chatB, chatA),
                dateFilters = linkedSetOf(dateB, dateA),
            )
        assertEquals(encodeGlobalSearchState(forward), encodeGlobalSearchState(reverse))
    }

    @Test
    fun malformedDecodeFallsBackToEmptyState() {
        assertEquals(GlobalSearchState(), decodeGlobalSearchState("not-a-valid-payload"))
        assertEquals(GlobalSearchState(), decodeGlobalSearchState(""))
        assertEquals(GlobalSearchState(), decodeGlobalSearchState("1\u001ftrue\u001f"))
        assertEquals(GlobalSearchState(), decodeGlobalSearchState("999\u001f"))
        assertEquals(
            GlobalSearchState(),
            decodeGlobalSearchState("2\u001ftrue\u001f%%%\u001ffalse\u001f\u001f\u001f\u001f\u001f"),
        )
    }
}
