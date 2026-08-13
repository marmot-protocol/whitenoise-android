package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GlobalSearchFilterInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun filtersActionDescriptionWithZeroCount() {
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state = GlobalSearchState(isOpen = true),
                    onOpenFilters = {},
                    onRemoveFilter = {},
                    onClearAll = {},
                )
            }
        }
        composeRule
            .onNodeWithTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG)
            .assertContentDescriptionEquals(context.getString(R.string.chat_list_search_filters))
    }

    @Test
    fun filtersActionDescriptionAndVisibleLabelHaveNonzeroCount() {
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state =
                        GlobalSearchState(
                            isOpen = true,
                            dateFilterSelection = GlobalSearchDateFilterSelection.Today,
                        ),
                    onOpenFilters = {},
                    onRemoveFilter = {},
                    onClearAll = {},
                )
            }
        }
        composeRule
            .onNodeWithTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG)
            .assertContentDescriptionEquals(
                context.resources.getQuantityString(R.plurals.chat_list_search_filters_active, 1, 1),
            ).assertTextEquals(
                context.getString(R.string.chat_list_search_filters_button, 1),
            )
    }

    @Test
    fun filtersActionOpensTheSheetState() {
        val stateHolder = mutableStateOf(GlobalSearchState(isOpen = true))
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state = stateHolder.value,
                    onOpenFilters = {
                        stateHolder.value = GlobalSearchTransitions.openFilterSheet(stateHolder.value)
                    },
                    onRemoveFilter = {},
                    onClearAll = {},
                )
            }
        }

        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG).performClick()
        composeRule.runOnIdle { assertTrue(stateHolder.value.filterSheetOpen) }
    }

    @Test
    fun unavailableFiltersActionIsHiddenWhileActiveFiltersRemainClearable() {
        val chat = GlobalSearchChatFilter("g1", "Alice")
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state = GlobalSearchState(isOpen = true, chatFilters = setOf(chat)),
                    onOpenFilters = null,
                    onRemoveFilter = {},
                    onClearAll = {},
                )
            }
        }

        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(globalSearchFilterChipTag(chat.chipId)).assertExists()
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_CLEAR_ALL_FILTERS_TAG).assertExists()
    }

    @Test
    fun emptyFilterSheetIsNotPresented() {
        composeRule.setContent {
            Surface {
                GlobalSearchFilterSheet(
                    visible = true,
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_SHEET_TAG).assertDoesNotExist()
    }

    @Test
    fun backDismissesFilterSheet() {
        val visible = mutableStateOf(true)
        composeRule.setContent {
            Surface {
                GlobalSearchFilterSheet(
                    visible = visible.value,
                    onDismiss = { visible.value = false },
                    chatSection = { Text("Chat controls") },
                )
            }
        }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_SHEET_TAG).assertExists()

        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("Filter sheet must own a ComponentDialog", dialog is ComponentDialog)
        composeRule.runOnUiThread {
            (dialog as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertFalse(visible.value) }
    }

    @Test
    fun removingActiveChipClearsOnlyThatFilter() {
        val chat = GlobalSearchChatFilter("g1", "Alice")
        val stateHolder =
            mutableStateOf(
                GlobalSearchState(
                    isOpen = true,
                    chatFilters = setOf(chat),
                    contentFilterSelection =
                        GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.LINKS)),
                ),
            )
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state = stateHolder.value,
                    onOpenFilters = {},
                    onRemoveFilter = { chipId ->
                        stateHolder.value = GlobalSearchTransitions.removeFilter(stateHolder.value, chipId)
                    },
                    onClearAll = {
                        stateHolder.value = GlobalSearchTransitions.clearAllFilters(stateHolder.value)
                    },
                )
            }
        }
        composeRule.onNodeWithTag(globalSearchFilterChipTag(chat.chipId)).performClick()
        composeRule.runOnIdle {
            assertTrue(stateHolder.value.chatFilters.isEmpty())
            assertEquals(
                setOf(GlobalSearchContentKind.LINKS),
                stateHolder.value.contentFilterSelection.selectedKinds,
            )
        }
    }

    @Test
    fun activeChipHasOneLocalizedRemovalDescription() {
        val chat = GlobalSearchChatFilter("g1", "Alice")
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state = GlobalSearchState(isOpen = true, chatFilters = setOf(chat)),
                    onOpenFilters = {},
                    onRemoveFilter = {},
                    onClearAll = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(globalSearchFilterChipTag(chat.chipId))
            .assertContentDescriptionEquals(
                context.getString(R.string.chat_list_search_filter_remove, chat.displayLabel),
            )
    }

    @Test
    fun clearAllRemovesEveryActiveFilter() {
        val stateHolder =
            mutableStateOf(
                GlobalSearchState(
                    isOpen = true,
                    chatFilters = setOf(GlobalSearchChatFilter("g1", "Alice")),
                    dateFilterSelection = GlobalSearchDateFilterSelection.Today,
                ),
            )
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state = stateHolder.value,
                    onOpenFilters = {},
                    onRemoveFilter = { chipId ->
                        stateHolder.value = GlobalSearchTransitions.removeFilter(stateHolder.value, chipId)
                    },
                    onClearAll = {
                        stateHolder.value = GlobalSearchTransitions.clearAllFilters(stateHolder.value)
                    },
                )
            }
        }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_CLEAR_ALL_FILTERS_TAG).performClick()
        composeRule.runOnIdle {
            assertTrue(stateHolder.value.chatFilters.isEmpty())
            assertEquals(GlobalSearchDateFilterSelection.AnyTime, stateHolder.value.dateFilterSelection)
        }
    }

    @Test
    fun customDateActiveChipShowsInclusiveRangeLabel() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 3)
        val custom =
            GlobalSearchDateFilterSelection.Custom(
                from = from,
                to = to,
                zoneId = ZoneId.of("UTC"),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    GlobalSearchFilterControlsRow(
                        state = GlobalSearchState(isOpen = true, dateFilterSelection = custom),
                        onOpenFilters = {},
                        onRemoveFilter = {},
                        onClearAll = {},
                    )
                }
            }
        }

        val formatter =
            DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.US)
        val expectedLabel =
            context.getString(
                R.string.global_search_date_custom_summary,
                from.format(formatter),
                to.format(formatter),
            )
        composeRule
            .onNodeWithTag(globalSearchFilterChipTag("date:custom"))
            .assertTextEquals(expectedLabel)
    }

    @Test
    fun clearAllButtonHasAccessibilityDescription() {
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state =
                        GlobalSearchState(
                            isOpen = true,
                            dateFilterSelection = GlobalSearchDateFilterSelection.Today,
                            contentFilterSelection =
                                GlobalSearchContentFilterSelection(setOf(GlobalSearchContentKind.TEXT)),
                        ),
                    onOpenFilters = {},
                    onRemoveFilter = {},
                    onClearAll = {},
                )
            }
        }
        composeRule
            .onNodeWithTag(CHAT_LIST_SEARCH_CLEAR_ALL_FILTERS_TAG)
            .assertContentDescriptionEquals(context.getString(R.string.chat_list_search_clear_all_filters))
    }
}
