package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

    /** Filter icon describes the inactive state without selection. */
    @Test
    fun filterIconDescribesTheInactiveStateWithoutSelection() {
        composeRule.setContent {
            Surface {
                GlobalSearchFilterIconButton(state = GlobalSearchState(isOpen = true), onClick = {})
            }
        }
        composeRule
            .onNodeWithTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG)
            .assertContentDescriptionEquals(context.getString(R.string.chat_list_search_filters))
            .assertIsNotSelected()
    }

    /** Active filter icon is selected with the active count. */
    @Test
    fun activeFilterIconIsSelectedWithTheActiveCount() {
        composeRule.setContent {
            Surface {
                GlobalSearchFilterIconButton(
                    state =
                        GlobalSearchState(
                            isOpen = true,
                            dateFilterSelection = GlobalSearchDateFilterSelection.Today,
                        ),
                    onClick = {},
                )
            }
        }
        composeRule
            .onNodeWithTag(CHAT_LIST_SEARCH_FILTERS_ACTION_TAG)
            .assertContentDescriptionEquals(
                context.resources.getQuantityString(R.plurals.chat_list_search_filters_active, 1, 1),
            ).assertIsSelected()
    }

    /** Menu category opens the picker state. */
    @Test
    fun menuCategoryOpensThePickerState() {
        val stateHolder = mutableStateOf(GlobalSearchState(isOpen = true))
        composeRule.setContent {
            Surface {
                GlobalSearchFilterMenu(
                    expanded = true,
                    state = stateHolder.value,
                    onDismiss = {},
                    onCategory = {
                        stateHolder.value = GlobalSearchTransitions.openFilterCategory(stateHolder.value, it)
                    },
                    onClearAll = {},
                )
            }
        }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_MENU_CLEAR_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(globalSearchFilterMenuItemTag(GlobalSearchFilterCategory.Folder)).performClick()
        composeRule.runOnIdle { assertEquals(GlobalSearchFilterCategory.Folder, stateHolder.value.openFilterCategory) }
    }

    /** Menu offers clear all only while filters are active. */
    @Test
    fun menuOffersClearAllOnlyWhileFiltersAreActive() {
        val stateHolder =
            mutableStateOf(
                GlobalSearchState(isOpen = true, chatTypeFilters = setOf(GlobalSearchChatType.GROUPS)),
            )
        composeRule.setContent {
            Surface {
                GlobalSearchFilterMenu(
                    expanded = true,
                    state = stateHolder.value,
                    onDismiss = {},
                    onCategory = {},
                    onClearAll = { stateHolder.value = GlobalSearchTransitions.clearAllFilters(stateHolder.value) },
                )
            }
        }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_MENU_CLEAR_TAG).performClick()
        composeRule.runOnIdle { assertFalse(stateHolder.value.hasActiveFilters) }
    }

    /** Folder picker toggles the folder and done dismisses. */
    @Test
    fun folderPickerTogglesTheFolderAndDoneDismisses() {
        val stateHolder =
            mutableStateOf(GlobalSearchState(isOpen = true, openFilterCategory = GlobalSearchFilterCategory.Folder))
        composeRule.setContent {
            GlobalSearchFilterPicker(
                state = stateHolder.value,
                options = GlobalSearchFilterOptions(folders = listOf(GlobalSearchFolderOption("f1", "Family"))),
                onStateChange = { transform -> stateHolder.value = transform(stateHolder.value) },
            )
        }
        composeRule.onNodeWithTag(CHAT_LIST_SEARCH_FILTER_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(globalSearchFilterOptionTag("f1")).performClick()
        composeRule.runOnIdle { assertEquals(setOf("f1"), stateHolder.value.folderFilters) }
        composeRule.onNodeWithText(context.getString(R.string.done)).performClick()
        composeRule.runOnIdle { assertFalse(stateHolder.value.filterSheetOpen) }
    }

    /** Empty folder picker explains that no folder exists. */
    @Test
    fun emptyFolderPickerExplainsThatNoFolderExists() {
        composeRule.setContent {
            GlobalSearchFilterPicker(
                state = GlobalSearchState(isOpen = true, openFilterCategory = GlobalSearchFilterCategory.Folder),
                options = GlobalSearchFilterOptions(),
                onStateChange = {},
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.chat_list_search_no_folders)).assertExists()
    }

    /** Chat type picker toggles direct chats. */
    @Test
    fun chatTypePickerTogglesDirectChats() {
        val stateHolder =
            mutableStateOf(GlobalSearchState(isOpen = true, openFilterCategory = GlobalSearchFilterCategory.ChatType))
        composeRule.setContent {
            GlobalSearchFilterPicker(
                state = stateHolder.value,
                options = GlobalSearchFilterOptions(),
                onStateChange = { transform -> stateHolder.value = transform(stateHolder.value) },
            )
        }
        composeRule.onNodeWithTag(globalSearchFilterOptionTag(GlobalSearchChatType.DIRECT.name)).performClick()
        composeRule.runOnIdle { assertEquals(setOf(GlobalSearchChatType.DIRECT), stateHolder.value.chatTypeFilters) }
    }

    /** Folder chip names the folder and type chip uses the prototype label. */
    @Test
    fun folderChipNamesTheFolderAndTypeChipUsesThePrototypeLabel() {
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state =
                        GlobalSearchState(
                            isOpen = true,
                            folderFilters = setOf("f1"),
                            chatTypeFilters = setOf(GlobalSearchChatType.GROUPS),
                        ),
                    folderNames = mapOf("f1" to "Family"),
                    onRemoveFilter = {},
                    onClearAll = {},
                )
            }
        }
        composeRule
            .onNodeWithTag(globalSearchFilterChipTag("folder:f1"))
            .assertTextEquals(context.getString(R.string.chat_list_search_folder_chip, "Family"))
        composeRule
            .onNodeWithTag(globalSearchFilterChipTag("type:GROUPS"))
            .assertTextEquals(context.getString(R.string.chat_list_search_groups))
    }

    /** Removing active chip clears only that filter. */
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

    /** Active chip has one localized removal description. */
    @Test
    fun activeChipHasOneLocalizedRemovalDescription() {
        val chat = GlobalSearchChatFilter("g1", "Alice")
        composeRule.setContent {
            Surface {
                GlobalSearchFilterControlsRow(
                    state = GlobalSearchState(isOpen = true, chatFilters = setOf(chat)),
                    onRemoveFilter = {},
                    onClearAll = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(globalSearchFilterChipTag(chat.chipId))
            .assertContentDescriptionEquals(
                context.getString(
                    R.string.chat_list_search_filter_remove,
                    context.getString(R.string.chat_list_search_chat_chip, chat.displayLabel),
                ),
            )
    }

    /** Clear all removes every active filter. */
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

    /** Custom date active chip shows inclusive range label. */
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

    /** Clear all button has accessibility description. */
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
