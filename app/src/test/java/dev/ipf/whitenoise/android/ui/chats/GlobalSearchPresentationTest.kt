package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.search.GlobalPersonRow
import dev.ipf.whitenoise.android.ui.search.GlobalSearchContentFilterChips
import dev.ipf.whitenoise.android.ui.search.globalSearchContentChipTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual header callbacks retain shell transitions; presentation cannot enable an unavailable search operation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GlobalSearchPresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Voice owns the empty trailing slot, and Clear returns the same shell state to an empty query. */
    @Test fun headerQueryClearAndVoiceUseTheExistingShellTransitions() {
        val state = mutableStateOf(GlobalSearchState(isOpen = true))
        var voices = 0
        val app = emptyApp()
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBar(
                    app,
                    state.value.isOpen,
                    state.value.query,
                    remember { FocusRequester() },
                    { state.value = GlobalSearchTransitions.setQuery(state.value, it) },
                    { state.value = GlobalSearchTransitions.openSearch(state.value) },
                    { state.value = GlobalSearchTransitions.closeSearch(state.value) },
                    { voices++ },
                    {},
                    {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_list_search_voice)).performClick()
        assertEquals(1, voices)
        composeRule.onNodeWithTag("chats.searchField").performTextReplacement("alice")
        assertEquals("alice", state.value.query)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.chat_list_search_voice))
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_list_search_clear)).performClick()
        assertEquals("", state.value.query)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_list_search_voice)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        assertFalse(state.value.isOpen)
    }

    /** Native filter unavailability removes the opening action but cannot prevent clearing restored filters. */
    @Test fun unavailableFiltersRemainRemovableWithoutOpeningFakeControls() {
        val state =
            mutableStateOf(
                GlobalSearchState(
                    isOpen = true,
                    chatFilters = setOf(GlobalSearchChatFilter("group", "Friends")),
                ),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                GlobalSearchFilterControlsRow(
                    state.value,
                    null,
                    { state.value = GlobalSearchTransitions.removeFilter(state.value, it) },
                    { state.value = GlobalSearchTransitions.clearAllFilters(state.value) },
                )
            }
        }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_CLEAR_ALL_FILTERS_TAG).performClick()
        assertTrue(state.value.chatFilters.isEmpty())
        assertTrue(state.value.isOpen)
        assertFalse(state.value.filterSheetOpen)
    }

    /** One native list action delivers the complete resolved key once, never the shortened visual label. */
    @Test fun identifierRowOpensTheExactResolvedPublicReference() {
        val key = "npub1" + "a".repeat(58)
        val opened = mutableListOf<String>()
        composeRule.setContent { WhiteNoiseTheme { GlobalPersonRow(key, opened::add) } }
        composeRule.onNodeWithTag("global.person." + key).performClick()
        assertEquals(listOf(key), opened)
    }

    /** Check rows write the existing canonical content selection and remain reachable at large text. */
    @Test fun contentRowsPreserveToggleAndClearSemanticsAtLargeText() {
        val selection = mutableStateOf(GlobalSearchContentFilterSelection())
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = 2f) {
                GlobalSearchContentFilterChips(selection.value, { selection.value = it })
            }
        }
        val tag = globalSearchContentChipTag(GlobalSearchContentKind.FILES_DOCUMENTS)
        composeRule
            .onNodeWithTag(tag)
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        assertTrue(GlobalSearchContentKind.FILES_DOCUMENTS in selection.value.selectedKinds)
        composeRule.onNodeWithTag(tag).performClick().assertIsNotSelected()
        assertTrue(selection.value.selectedKinds.isEmpty())
    }

    /** No account/runtime is bootstrapped to exercise the actual header's independent presentation callbacks. */
    private fun emptyApp() =
        WhiteNoiseAppState(
            context,
            DraftStore(
                object : DraftPersistence {
                    override fun read(): Map<String, String> = emptyMap()

                    override fun write(
                        key: String,
                        value: String?,
                    ) = Unit
                },
            ),
            { null },
            emptyList(),
            "",
        )
}
