package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.saveable.SaverScope
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchChatFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchChatType
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterCategory
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchSenderFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchStateSaver
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchFiltersTest {
    /** Removing or broadening the entry preset keeps the user's query, sender and date selections. */
    @Test
    fun chatPresetIsRemovableWithoutLosingTheRequest() {
        val initial = conversationSearchPreset("account", 1, "chat", "Community")
        assertTrue(keepsConversationSearchNavigator(initial, "chat"))
        val filtered =
            initial.copy(
                query = "needle",
                senderFilters = setOf(GlobalSearchSenderFilter("author", "Alice")),
                dateFilterSelection = GlobalSearchDateFilterSelection.Last7Days,
            )
        val removed = GlobalSearchTransitions.removeFilter(filtered, "chat:chat")
        assertFalse(keepsConversationSearchNavigator(removed, "chat"))
        assertEquals("needle", removed.query)
        assertEquals(filtered.senderFilters, removed.senderFilters)
        assertEquals(filtered.dateFilterSelection, removed.dateFilterSelection)
        assertFalse(
            keepsConversationSearchNavigator(
                initial.copy(
                    chatFilters =
                        initial.chatFilters +
                            GlobalSearchChatFilter("other", "Other"),
                ),
                "chat",
            ),
        )
    }

    /** Rotation preserves all filter state, but another account, chat or runtime rejects that state. */
    @Test
    fun rotationKeepsRemovedPresetsAndAllFilterCategoriesWithinTheirOwner() {
        val state =
            conversationSearchPreset("account", 2, "chat", "Community").copy(
                query = "needle",
                chatFilters = emptySet(),
                folderFilters = setOf("folder"),
                senderFilters = setOf(GlobalSearchSenderFilter("author", "Alice")),
                openFilterCategory = GlobalSearchFilterCategory.Date,
                dateFilterSelection = GlobalSearchDateFilterSelection.Last7Days,
                contentFilterSelection = GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.LINKS)),
            )
        val original = ConversationSurfaceState(searchInitially = state)
        val saver = conversationSurfaceStateSaver("account", "chat", 2)
        val saved = with(saver) { requireNotNull(SaverScope { true }.save(original)) }
        val restored = requireNotNull(saver.restore(saved))
        assertTrue(restored.searchOpen.value)
        assertEquals(state, restored.searchState.value)
        for (otherSaver in listOf(
            conversationSurfaceStateSaver("other", "chat", 2),
            conversationSurfaceStateSaver("account", "other", 2),
            conversationSurfaceStateSaver("account", "chat", 3),
        )) {
            val other = requireNotNull(otherSaver.restore(saved))
            assertFalse(other.searchOpen.value)
            assertFalse(other.searchState.value.hasActiveFilters)
            assertEquals("", other.searchState.value.query)
        }
    }

    /** A blank query with active filters still exposes real scan loading, completion and failure. */
    @Test
    fun filterOnlyScansHaveRealLoadingCompletionAndFailureStates() {
        assertEquals(ConversationSearchScanStatus.LOADING, conversationSearchScanStatus("", null, false, true))
        assertEquals(ConversationSearchScanStatus.COMPLETE, conversationSearchScanStatus("", emptyList(), false, true))
        assertEquals(ConversationSearchScanStatus.FAILED, conversationSearchScanStatus("", null, true, true))
        assertEquals(ConversationSearchScanStatus.IDLE, conversationSearchScanStatus("", null, false))
    }

    /** Clearing message filters never removes the named chat or changes query/account ownership. */
    @Test
    fun clearKeepsConversationScopeAndQuery() {
        val state =
            conversationSearchPreset("account", 3, "chat", "Community").copy(
                query = "needle",
                senderFilters = setOf(GlobalSearchSenderFilter("author", "Alice")),
                dateFilterSelection = GlobalSearchDateFilterSelection.Last7Days,
                contentFilterSelection = GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.LINKS)),
            )
        val cleared = clearConversationSearchFilters(state)
        assertEquals(state.chatFilters, cleared.chatFilters)
        assertEquals(state.accountScopeToken, cleared.accountScopeToken)
        assertEquals(state.query, cleared.query)
        assertTrue(keepsConversationSearchNavigator(cleared, "chat"))
        assertFalse(cleared.messageFiltersActive)
    }

    /** Explicit broadening preserves message intent and removes only cross-chat restrictions. */
    @Test
    fun explicitBroadeningKeepsCompatibleFiltersAndAccountOwner() {
        val state =
            conversationSearchPreset("account", 3, "chat", "Community").copy(
                query = "needle",
                folderFilters = setOf("folder"),
                chatTypeFilters = setOf(GlobalSearchChatType.DIRECT),
                senderFilters = setOf(GlobalSearchSenderFilter("author", "Alice")),
                dateFilterSelection = GlobalSearchDateFilterSelection.Last7Days,
                contentFilterSelection = GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.LINKS)),
            )
        val broader = broadenConversationSearch(state)
        assertEquals(state.query, broader.query)
        assertEquals(state.senderFilters, broader.senderFilters)
        assertEquals(state.dateFilterSelection, broader.dateFilterSelection)
        assertEquals(state.contentFilterSelection, broader.contentFilterSelection)
        assertEquals(state.accountScopeToken, broader.accountScopeToken)
        assertTrue(broader.folderFilters.isEmpty())
        assertTrue(broader.chatTypeFilters.isEmpty())
        assertTrue(broader.chatFilters.isEmpty())
        assertFalse(keepsConversationSearchNavigator(broader, "chat"))
    }

    /** The canonical saver retains contradictory selections; restoration cannot manufacture results. */
    @Test
    fun restoredContradictionsRemainInTheCanonicalRequest() {
        val state =
            conversationSearchPreset("account", 3, "group", "Community").copy(
                chatTypeFilters = setOf(GlobalSearchChatType.DIRECT),
                folderFilters = setOf("different-folder"),
            )
        val saved = with(GlobalSearchStateSaver) { requireNotNull(SaverScope { true }.save(state)) }
        assertEquals(state, GlobalSearchStateSaver.restore(saved))
        val cleared = clearConversationSearchFilters(state)
        assertEquals(state.chatFilters, cleared.chatFilters)
        assertTrue(cleared.chatTypeFilters.isEmpty())
        assertTrue(cleared.folderFilters.isEmpty())
    }
}
