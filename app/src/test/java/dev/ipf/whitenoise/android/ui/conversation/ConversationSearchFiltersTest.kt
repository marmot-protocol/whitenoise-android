package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.saveable.SaverScope
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchChatFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterCategory
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchSenderFilter
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchFiltersTest {
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

    @Test
    fun filterOnlyScansHaveRealLoadingCompletionAndFailureStates() {
        assertEquals(ConversationSearchScanStatus.LOADING, conversationSearchScanStatus("", null, false, true))
        assertEquals(ConversationSearchScanStatus.COMPLETE, conversationSearchScanStatus("", emptyList(), false, true))
        assertEquals(ConversationSearchScanStatus.FAILED, conversationSearchScanStatus("", null, true, true))
        assertEquals(ConversationSearchScanStatus.IDLE, conversationSearchScanStatus("", null, false))
    }
}
