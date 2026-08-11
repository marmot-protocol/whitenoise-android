package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.ui.search.GLOBAL_SEARCH_CONTENT_FILTER_TAG
import dev.ipf.whitenoise.android.ui.search.GLOBAL_SEARCH_DATE_APPLY_TAG
import dev.ipf.whitenoise.android.ui.search.GLOBAL_SEARCH_DATE_DIALOG_TAG
import dev.ipf.whitenoise.android.ui.search.GLOBAL_SEARCH_DATE_FILTER_SECTION_TAG
import dev.ipf.whitenoise.android.ui.search.globalSearchContentChipTag
import dev.ipf.whitenoise.android.ui.search.globalSearchDatePresetTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Integration regression: typed date/content filters must live in [GlobalSearchState],
 * round-trip through the shell saver, and wire through the real filter sheet controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlobalSearchTypedFilterIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun typedFiltersApplyRemoveAndRoundTripThroughStateSaver() {
        var state =
            GlobalSearchState(
                isOpen = true,
                query = "needle",
            )
        state =
            GlobalSearchTransitions.applyDateFilter(
                state,
                GlobalSearchDateFilterSelection.Last7Days,
            )
        state =
            GlobalSearchTransitions.setContentFilterSelection(
                state,
                GlobalSearchContentFilterSelection(
                    selectedKinds =
                        setOf(
                            GlobalSearchContentKind.TEXT,
                            GlobalSearchContentKind.LINKS,
                        ),
                ),
            )

        val encoded = encodeGlobalSearchState(state)
        val restored = decodeGlobalSearchState(encoded)
        assertEquals(GlobalSearchDateFilterSelection.Last7Days, restored.dateFilterSelection)
        assertEquals(
            setOf(GlobalSearchContentKind.TEXT, GlobalSearchContentKind.LINKS),
            restored.contentFilterSelection.selectedKinds,
        )

        val chipIds = GlobalSearchActiveChips.from(restored).items.map { it.chipId }
        assertTrue(chipIds.contains("date:last7"))
        assertTrue(chipIds.contains("content:TEXT"))
        assertTrue(chipIds.contains("content:LINKS"))

        var afterRemoveDate = GlobalSearchTransitions.removeFilter(restored, "date:last7")
        assertEquals(GlobalSearchDateFilterSelection.AnyTime, afterRemoveDate.dateFilterSelection)
        assertEquals(
            setOf(GlobalSearchContentKind.TEXT, GlobalSearchContentKind.LINKS),
            afterRemoveDate.contentFilterSelection.selectedKinds,
        )

        var afterRemoveContent = GlobalSearchTransitions.removeFilter(afterRemoveDate, "content:TEXT")
        assertEquals(setOf(GlobalSearchContentKind.LINKS), afterRemoveContent.contentFilterSelection.selectedKinds)

        val cleared = GlobalSearchTransitions.clearAllFilters(afterRemoveContent)
        assertEquals(GlobalSearchDateFilterSelection.AnyTime, cleared.dateFilterSelection)
        assertTrue(cleared.contentFilterSelection.selectedKinds.isEmpty())
        assertEquals("needle", cleared.query)
        assertTrue(cleared.isOpen)

        val projection =
            state.projectSearchRequest(
                zoneId = ZoneId.of("America/New_York"),
                nowMillis = FIXED_NOW_MILLIS,
            )
        assertTrue(projection.requiresTypedMdkContract)
        assertEquals(setOf(GlobalSearchContentKind.TEXT, GlobalSearchContentKind.LINKS), projection.contentKinds)
    }

    @Test
    fun filterSheetContentChipsAndDateDialogUpdateShellOwnedState() {
        val stateHolder =
            mutableStateOf(
                GlobalSearchState(
                    isOpen = true,
                    filterSheetOpen = true,
                ),
            )

        composeRule.setContent {
            GlobalSearchTypedFilterSheet(
                state = stateHolder.value,
                onStateChange = { transform -> stateHolder.value = transform(stateHolder.value) },
                nowMillis = { FIXED_NOW_MILLIS },
                zoneId = { ZoneId.of("UTC") },
            )
        }

        composeRule
            .onNodeWithTag(GLOBAL_SEARCH_CONTENT_FILTER_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(globalSearchContentChipTag(GlobalSearchContentKind.VOICE_AUDIO))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                setOf(GlobalSearchContentKind.VOICE_AUDIO),
                stateHolder.value.contentFilterSelection.selectedKinds,
            )
        }

        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_FILTER_SECTION_TAG).performClick()
        composeRule
            .onNodeWithTag(globalSearchDatePresetTag(GlobalSearchDateFilterSelection.Today))
            .performClick()
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_APPLY_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals(GlobalSearchDateFilterSelection.Today, stateHolder.value.dateFilterSelection)
            assertEquals(
                setOf(GlobalSearchContentKind.VOICE_AUDIO),
                stateHolder.value.contentFilterSelection.selectedKinds,
            )
        }

        val restored = decodeGlobalSearchState(encodeGlobalSearchState(stateHolder.value))
        assertEquals(GlobalSearchDateFilterSelection.Today, restored.dateFilterSelection)
        assertEquals(
            setOf(GlobalSearchContentKind.VOICE_AUDIO),
            restored.contentFilterSelection.selectedKinds,
        )
        assertTrue(restored.filterSheetOpen)
    }

    @Test
    fun dateDialogDoesNotSurviveSearchOrFilterSheetDismissal() {
        val stateHolder =
            mutableStateOf(
                GlobalSearchState(
                    isOpen = true,
                    filterSheetOpen = true,
                ),
            )

        composeRule.setContent {
            GlobalSearchTypedFilterSheet(
                state = stateHolder.value,
                onStateChange = { transform -> stateHolder.value = transform(stateHolder.value) },
                nowMillis = { FIXED_NOW_MILLIS },
                zoneId = { ZoneId.of("UTC") },
            )
        }

        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_FILTER_SECTION_TAG).performClick()
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_DIALOG_TAG).assertExists()

        composeRule.runOnIdle {
            stateHolder.value = GlobalSearchTransitions.closeSearch(stateHolder.value)
        }
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_DIALOG_TAG).assertDoesNotExist()

        composeRule.runOnIdle {
            stateHolder.value = GlobalSearchTransitions.openSearch(stateHolder.value)
            stateHolder.value = GlobalSearchTransitions.openFilterSheet(stateHolder.value)
        }
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_DIALOG_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_FILTER_SECTION_TAG).performClick()
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_DIALOG_TAG).assertExists()

        composeRule.runOnIdle {
            stateHolder.value = GlobalSearchTransitions.dismissFilterSheet(stateHolder.value)
        }
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_DIALOG_TAG).assertDoesNotExist()

        composeRule.runOnIdle {
            stateHolder.value = GlobalSearchTransitions.openFilterSheet(stateHolder.value)
        }
        composeRule.onNodeWithTag(GLOBAL_SEARCH_DATE_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun customDateFilterRoundTripsThroughSaver() {
        val custom =
            GlobalSearchDateFilterSelection.Custom(
                from = LocalDate.of(2026, 7, 1),
                to = LocalDate.of(2026, 7, 3),
                zoneId = ZoneId.of("America/Los_Angeles"),
            )
        val state =
            GlobalSearchState(
                isOpen = true,
                dateFilterSelection = custom,
            )
        val restored = decodeGlobalSearchState(encodeGlobalSearchState(state))
        assertEquals(custom, restored.dateFilterSelection)
        assertEquals(
            "date:custom",
            GlobalSearchActiveChips
                .from(restored)
                .items
                .single()
                .chipId,
        )
    }

    private companion object {
        private const val FIXED_NOW_MILLIS = 1_786_462_800_000L
    }
}
