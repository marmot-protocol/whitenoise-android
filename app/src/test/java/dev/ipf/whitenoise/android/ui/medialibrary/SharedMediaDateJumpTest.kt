package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/** The calendar offers the filtered grid's loaded days and only moves that grid. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SharedMediaDateJumpTest {
    @get:Rule val composeRule = createComposeRule()

    /** A day resolves to its first tile, or to its month header when it opens the section, without reordering. */
    @Test
    fun dayResolvesToFirstTileAndRepeatedMonthKeepsNativeOrder() {
        val rows = listOf(entry(1, NOV), entry(2, NOV_10), entry(3, SEP), entry(4, NOV_10), entry(5, JUN))
        val sections = attachmentLibraryTiles(rows, null).visualSections
        assertEquals(rows.map { it.messageIdHex }, sections.flatMap { it.items }.map { it.messageIdHex })
        assertEquals(setOf(DAY_NOV, DAY_NOV_10, DAY_SEP, DAY_JUN), sections.mediaDays())
        assertEquals(0, sections.dayIndex(DAY_NOV))
        assertEquals(2, sections.dayIndex(DAY_NOV_10))
        assertEquals(3, sections.dayIndex(DAY_SEP))
        assertEquals(7, sections.dayIndex(DAY_JUN))
        assertNull(sections.dayIndex(DAY_JUN.minusDays(1)))
        assertEquals(DAY_NOV, sections.dayAt(0))
        assertEquals(DAY_NOV_10, sections.dayAt(2))
        assertEquals(DAY_SEP, sections.dayAt(3))
    }

    /** Each filter offers only the days its own grid contains, and a loaded page adds its days. */
    @Test
    fun filtersAndPagesChangeTheSelectableDays() =
        runBlocking {
            val video = AttachmentCategoryFfi.VIDEO
            val tiles = attachmentLibraryTiles(listOf(entry(1, NOV), entry(2, SEP, video), entry(3, MAY_2022)), null)
            assertEquals(setOf(DAY_NOV, DAY_MAY_2022), tiles.imageSections.mediaDays())
            assertEquals(setOf(DAY_SEP), tiles.videoSections.mediaDays())
            val source = GroupAttachmentFixture((1..3).map { entry(it, NOV) } + entry(4, MAY_2022), size = 3)
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            assertEquals(setOf(DAY_NOV), attachmentLibraryTiles(pager.state.entries, null).visualSections.mediaDays())
            pager.loadMore()
            assertEquals(
                setOf(DAY_NOV, DAY_MAY_2022),
                attachmentLibraryTiles(pager.state.entries, null).visualSections.mediaDays(),
            )
            pager.close()
        }

    /** The calendar opens on the viewed day, refuses empty days, and Show moves the grid with no history read. */
    @Test
    fun chosenDayIsShownWithoutHistoryRead() {
        val rows = (1..10).map { entry(it, NOV) } + (11..40).map { entry(it, NOV_10) } + entry(41, SEP)
        val source = GroupAttachmentFixture(rows, size = 60)
        val pager = GroupAttachmentPager(source)
        runBlocking { pager.refresh() }
        lateinit var grid: LazyGridState
        var loadMoreRequests = 0
        composeRule.setContent { WhiteNoiseTheme { grid = DateJumpHarness(pager.state) { loadMoreRequests++ } } }
        composeRule
            .onNodeWithTag("shared.date.jump")
            .assertContentDescriptionEquals("Jump to date")
            .performClick()
        composeRule.onNode(hasText("November 12, 2023", substring = true)).assertIsNotEnabled()
        val reads = source.calls
        composeRule.onNode(hasText("November 10, 2023", substring = true)).performClick()
        composeRule.onNodeWithTag("shared.date.show").performClick()
        composeRule.waitForIdle()
        assertInTopRow(grid, 11)
        assertEquals(reads, source.calls)
        assertEquals(0, loadMoreRequests)
        pager.close()
    }

    /** A day too close to the end to reach the top still ends with its tiles on screen. */
    @Test
    fun shortOldestDayStaysOnScreen() {
        val rows = (1..30).map { entry(it, NOV) } + entry(31, NOV_10)
        composeRule.setContent { WhiteNoiseTheme { DateJumpHarness(loaded(rows)) } }
        composeRule.onNodeWithTag("shared.date.jump").performClick()
        composeRule.onNode(hasText("November 10, 2023", substring = true)).performClick()
        composeRule.onNodeWithTag("shared.date.show").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("message-31").assertIsDisplayed()
    }

    /** A chosen day that a refresh removes can no longer be confirmed. */
    @Test
    fun removedDayCannotBeConfirmed() {
        val kept = (1..10).map { entry(it, NOV) } + entry(12, SEP)
        var state by mutableStateOf(loaded(kept + entry(11, NOV_10)))
        composeRule.setContent { WhiteNoiseTheme { DateJumpHarness(state) } }
        composeRule.onNodeWithTag("shared.date.jump").performClick()
        composeRule.onNode(hasText("November 10, 2023", substring = true)).performClick()
        composeRule.onNodeWithTag("shared.date.show").assertIsEnabled()
        state = loaded(kept)
        composeRule.onNodeWithTag("shared.date.show").assertIsNotEnabled()
    }

    /** A day chosen while a refresh briefly replaces the grid is applied once the grid returns. */
    @Test
    fun choiceDuringRefreshStillJumps() {
        val rows = (1..10).map { entry(it, NOV) } + (11..40).map { entry(it, NOV_10) }
        var projecting by mutableStateOf(false)
        lateinit var grid: LazyGridState
        composeRule.setContent { WhiteNoiseTheme { grid = DateJumpHarness(loaded(rows), projecting) } }
        composeRule.onNodeWithTag("shared.date.jump").performClick()
        composeRule.onNode(hasText("November 10, 2023", substring = true)).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("shared.date.show").performClick()
        projecting = true
        composeRule.mainClock.advanceTimeBy(1_000)
        projecting = false
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertInTopRow(grid, 11)
    }

    /** A day chosen while a refresh restores its pages one at a time waits for its page to come back. */
    @Test
    fun choiceDuringPagedRestoreWaitsForItsPage() {
        val rows = (1..10).map { entry(it, NOV) } + (11..40).map { entry(it, NOV_10) }
        var state by mutableStateOf(loaded(rows))
        lateinit var grid: LazyGridState
        composeRule.setContent { WhiteNoiseTheme { grid = DateJumpHarness(state) } }
        composeRule.onNodeWithTag("shared.date.jump").performClick()
        composeRule.onNode(hasText("November 10, 2023", substring = true)).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("shared.date.show").performClick()
        state = GroupAttachmentState(rows.take(10), loading = true, initialized = true)
        composeRule.mainClock.advanceTimeBy(1_000)
        state = loaded(rows)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertInTopRow(grid, 11)
    }

    /** An open calendar keeps an older year's choice while a refresh restores the pages holding it. */
    @Test
    fun openCalendarKeepsItsChoiceThroughPagedRestore() {
        val rows = (1..10).map { entry(it, NOV) } + (11..40).map { entry(it, MAY_2022) }
        var state by mutableStateOf(loaded(rows))
        lateinit var grid: LazyGridState
        composeRule.setContent { WhiteNoiseTheme { grid = DateJumpHarness(state) } }
        composeRule.runOnIdle { runBlocking { grid.scrollToItem(12) } }
        composeRule.onNodeWithTag("shared.date.jump").performClick()
        composeRule.waitForIdle()
        state = GroupAttachmentState(rows.take(10), loading = true, initialized = true)
        composeRule.waitForIdle()
        state = loaded(rows)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shared.date.show").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertInTopRow(grid, 11)
    }

    /** Month and year follow the locale's own order and stay Gregorian. */
    @Test
    fun monthLabelFollowsLocaleOrder() {
        assertEquals("November 2023", monthLabel(NOV_KEY, Locale.US))
        assertEquals("2023年11月", monthLabel(NOV_KEY, Locale.JAPAN))
        assertTrue(monthLabel(NOV_KEY, Locale.forLanguageTag("zh-CN-u-ca-chinese")).startsWith("2023年"))
        assertEquals("November 2023", monthLabel(NOV_KEY, Locale.forLanguageTag("en-US-u-ca-japanese")))
        assertTrue(monthLabel(NOV_KEY, Locale.forLanguageTag("ar-EG-u-nu-latn")).contains("2023"))
    }

    /** A choice waiting on the grid is dropped when a refresh removes its day, leaving the grid in place. */
    @Test
    fun pendingJumpIsDiscardedWhenItsDayDisappears() {
        val before = attachmentLibraryTiles((1..30).map { entry(it, NOV) } + entry(31, SEP), null).visualSections
        val after = attachmentLibraryTiles((1..30).map { entry(it, NOV) }, null).visualSections
        var sections by mutableStateOf(before)
        var ready by mutableStateOf(false)
        var pending by mutableStateOf<LocalDate?>(DAY_SEP)
        lateinit var grid: LazyGridState
        composeRule.setContent {
            WhiteNoiseTheme {
                grid = rememberLazyGridState()
                MediaDateJumpEffect(pending, sections, grid, ready) { pending = null }
                MediaTileGrid(sections, grid, "No media") { Text(it.messageIdHex) }
            }
        }
        composeRule.waitForIdle()
        assertEquals(DAY_SEP, pending)
        sections = after
        ready = true
        composeRule.waitForIdle()
        assertNull(pending)
        assertEquals(0, grid.firstVisibleItemIndex)
    }
}

/** Production scaffold, calendar, effect and grid, with plain tiles in place of decrypted thumbnails. */
@Composable
internal fun DateJumpHarness(
    state: GroupAttachmentState,
    projecting: Boolean = false,
    onLoadMore: () -> Unit = {},
): LazyGridState {
    // Like MediaLibraryRoute, a projection in progress offers no sections and is not ready to jump.
    val sections = if (projecting) emptyList() else attachmentLibraryTiles(state.entries, null).visualSections
    val grid = rememberLazyGridState()
    var pending by remember { mutableStateOf<LocalDate?>(null) }
    val ready = !projecting && !state.loading
    MediaDateJumpEffect(pending, sections, grid, ready) { pending = null }
    SharedContentScaffold(
        SharedContentCategory.Media,
        SharedVisualFilter.All,
        {},
        {},
        loading = false,
        paging = state,
        onLoadMore = onLoadMore,
        filterRowAction = { MediaDateJumpAction(sections, grid, ready) { pending = it } },
    ) {
        MediaTileGrid(sections, grid, "No media") {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) { Text(it.messageIdHex) }
        }
    }
    return grid
}

/** The lazy grid scrolls whole rows, so a day's first tile lands in the top row beside earlier tiles. */
private fun assertInTopRow(
    grid: LazyGridState,
    index: Int,
) {
    val visible = grid.layoutInfo.visibleItemsInfo
    val top = visible.first { it.index == grid.firstVisibleItemIndex }
    assertEquals(top.offset.y, visible.first { it.index == index }.offset.y)
}

private fun loaded(rows: List<AttachmentEntryFfi>) = GroupAttachmentState(rows, loading = false, initialized = true)

/** Noon UTC keeps each fixture on the same calendar day in every test time zone between UTC-11 and UTC+11. */
internal fun entry(
    id: Int,
    at: ULong,
    category: AttachmentCategoryFfi = AttachmentCategoryFfi.IMAGE,
): AttachmentEntryFfi = historyEntry(id, category = category).copy(timelineAt = at)

internal const val NOV = 1_700_049_600UL
internal const val NOV_10 = 1_699_617_600UL
internal const val SEP = 1_694_779_200UL
internal const val JUN = 1_686_830_400UL
internal const val MAY_2022 = 1_652_616_000UL
internal const val NOV_KEY = 202310
internal val DAY_NOV: LocalDate = LocalDate.of(2023, 11, 15)
internal val DAY_NOV_10: LocalDate = LocalDate.of(2023, 11, 10)
internal val DAY_SEP: LocalDate = LocalDate.of(2023, 9, 15)
internal val DAY_JUN: LocalDate = LocalDate.of(2023, 6, 15)
internal val DAY_MAY_2022: LocalDate = LocalDate.of(2022, 5, 15)
