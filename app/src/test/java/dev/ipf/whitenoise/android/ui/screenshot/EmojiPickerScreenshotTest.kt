package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.ui.EmojiCategory
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerEmojiPickerFallbackHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerEmojiPickerPane
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_CATEGORIES_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_CELL_GLYPH_FILL_FRACTION
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_SEARCH_OPEN_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_SEARCH_ROW_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_SEARCH_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerContent
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerEmojiSize
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiPickerMinimumCellSize
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiSearchRowHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerCategoryTestTag
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerCellTextMetrics
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerHeaderTestTag
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerItemTestTag
import dev.ipf.whitenoise.android.ui.theme.Typography
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.scaledBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The shared emoji picker follows the prototype sheet: an adaptive grid of 48dp cells holding
 * 32dp glyphs, section headers at least 36dp tall, no gap between rows, and the same layout in
 * browse (Recent + Smileys) and sheet search modes. The composer pane's search shows one row of
 * matches above the field instead, so it fits above the keyboard (#3613).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class EmojiPickerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Resolves a string resource in the test context. */
    private fun string(resId: Int): String = context.getString(resId)

    /** Seeds three recents so the browse grid opens with the Recent section. */
    @Before
    fun seedRecentEmojis() {
        context
            .getSharedPreferences("whitenoise_ui", Context.MODE_PRIVATE)
            .edit()
            .putString("recent_reaction_emojis", "🔥\n❤️\n👍")
            .apply()
    }

    /** Browse mode packs seven 48dp cells per row under a 36dp header at 360dp. */
    @Test
    fun browseGridUsesAdaptiveCellsWithSectionHeaders() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        assertBrowseGridLayout()
    }

    /**
     * The composer pane opens with the first section at its top and no search bar; the rail along the
     * bottom holds search in its start corner and spreads the category toggles evenly.
     */
    @Test
    fun composerBrowseKeepsSearchInTheBottomRail() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_TEST_TAG).assertDoesNotExist()
        val pane = boundsOfTag(PICKER_TAG)
        val firstHeader = boundsOfTag(emojiPickerHeaderTestTag(EmojiCategory.Recent))
        assertTrue(
            "The first section starts at the pane's top (${firstHeader.top}px, pane ${pane.top}px)",
            firstHeader.top - pane.top <= (EXPECTED_HEADER_MIN_HEIGHT + EXPECTED_HEADER_TOP_GAP).value,
        )
        val rail = boundsOfTag(EMOJI_PICKER_CATEGORIES_TEST_TAG)
        val searchIcon = boundsOfTag(EMOJI_PICKER_SEARCH_OPEN_TEST_TAG)
        assertTrue("Search sits in the bottom-start corner", searchIcon.left - pane.left <= GAP_TOLERANCE_PX * 4)
        assertTrue("Search shares the rail's row", searchIcon.top < rail.bottom && searchIcon.bottom > rail.top)
        assertTrue("The rail sits on the pane's bottom edge", pane.bottom - searchIcon.bottom <= GAP_TOLERANCE_PX * 8)
        val toggles =
            listOf(EmojiCategory.Recent, EmojiCategory.SmileysAndPeople, EmojiCategory.AnimalsAndNature)
                .map { boundsOfTag(emojiPickerCategoryTestTag(it)) }
        assertEquals(
            "Category toggles are evenly spaced",
            toggles[1].left - toggles[0].left,
            toggles[2].left - toggles[1].left,
            GAP_TOLERANCE_PX,
        )
    }

    /** The sheets' default picker content keeps the sectioned search grid and drops the Recent section. */
    @Test
    fun sheetSearchKeepsGrid() {
        composeRule.setContent {
            WhiteNoiseTheme {
                EmojiPickerContent(
                    onEmojiPicked = {},
                    recentEmojis = RecentEmojiPreferences.load(context),
                    modifier = Modifier.width(PICKER_WIDTH).height(REACTION_SHEET_HEIGHT),
                )
            }
        }
        waitForBrowseGrid()
        openSearchAndType("happy", fromIcon = false)
        composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_ROW_TEST_TAG).assertDoesNotExist()
        assertSearchGridLayout(columns = SHEET_COLUMN_COUNT)
    }

    /** Composer search lays every match out on one row of 48dp cells below the search field. */
    @Test
    fun composerSearchUsesOneRowBelowTheField() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchRowLayout(rtl = false)
    }

    /** With no matches the row keeps its height and shows the no-results text. */
    @Test
    fun composerSearchRowKeepsItsHeightWithoutMatches() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_OPEN_TEST_TAG).performClick()
        composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_TEST_TAG).performTextInput("zzzzqqqq")
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithText(string(R.string.emoji_search_no_results)).assertIsDisplayed() }
                .isSuccess
        }
        val row = boundsOfTag(EMOJI_PICKER_SEARCH_ROW_TEST_TAG)
        assertEquals("No-results row height", EmojiSearchRowHeight.value, row.height, CELL_SIZE_TOLERANCE_PX)
    }

    /** Extra-large fonts shrink the glyph to fit the 32dp square instead of overlapping neighbours. */
    @Test
    fun reactionSheetCellGlyphsDoNotOverlapAtExtraLargeFontScale() {
        renderReactionSheetBrowsePane()
        waitForBrowseGrid()

        val appliedLayout = textLayoutResult(FIRST_SMILEYS_ROW.first())
        val glyphBoxPx =
            appliedLayout.layoutInput.constraints.maxWidth
                .toFloat()
        assertEquals(
            "Emoji glyphs are fitted into the 32dp prototype square",
            EmojiPickerEmojiSize.value,
            glyphBoxPx,
            CELL_SIZE_TOLERANCE_PX,
        )
        val typography = Typography.scaledBy(AppFontScale.ExtraLarge.factor)
        val uncappedLineHeightPx = typography.headlineMedium.lineHeight.value * REACTION_SHEET_OS_FONT_SCALE
        assertTrue(
            "Uncapped emoji line height would overflow the glyph square",
            uncappedLineHeightPx > glyphBoxPx,
        )

        val (fontSize, lineHeight) =
            emojiPickerCellTextMetrics(
                cellSizeDp = EmojiPickerEmojiSize,
                baseStyle = typography.headlineMedium,
                densityFontScale = REACTION_SHEET_OS_FONT_SCALE,
            )
        val cappedLineHeightPx = lineHeight.value * REACTION_SHEET_OS_FONT_SCALE
        assertTrue(
            "Expected capped line height ${cappedLineHeightPx}px to fit " +
                "${glyphBoxPx * EMOJI_GLYPH_FILL_FRACTION}px of glyph square",
            cappedLineHeightPx <= glyphBoxPx * EMOJI_GLYPH_FILL_FRACTION + CELL_SIZE_TOLERANCE_PX,
        )
        assertTrue(
            "Expected capped font size to shrink below uncapped headlineMedium at Extra Large",
            fontSize.value < typography.headlineMedium.fontSize.value,
        )

        val appliedStyle = appliedLayout.layoutInput.style
        assertEquals("Emoji cell must apply the fitted font size", fontSize, appliedStyle.fontSize)
        assertEquals("Emoji cell must apply the fitted line height", lineHeight, appliedStyle.lineHeight)

        assertNoHorizontalOverlap(FIRST_SMILEYS_ROW)
        assertStartsNextRow(FIRST_SMILEYS_ROW.last(), SECOND_SMILEYS_ROW.first())
    }

    /** Light browse baseline. */
    @Test
    fun browseRecentAndSmileysLight() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        assertBrowseGridLayout()
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_browse_light.png")
    }

    /** Dark browse baseline. */
    @Test
    fun browseRecentAndSmileysDark() {
        renderBrowsePane(darkTheme = true, amoled = false)
        waitForBrowseGrid()
        assertBrowseGridLayout()
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_browse_dark.png")
    }

    /** AMOLED browse baseline. */
    @Test
    fun browseRecentAndSmileysAmoled() {
        renderBrowsePane(darkTheme = true, amoled = true)
        waitForBrowseGrid()
        assertBrowseGridLayout()
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_browse_amoled.png")
    }

    /** Light composer search-row baseline. */
    @Test
    fun searchResultsLight() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchRowLayout(rtl = false)
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_search_light.png")
    }

    /** Dark composer search-row baseline. */
    @Test
    fun searchResultsDark() {
        renderBrowsePane(darkTheme = true, amoled = false)
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchRowLayout(rtl = false)
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_search_dark.png")
    }

    /** AMOLED composer search-row baseline. */
    @Test
    fun searchResultsAmoled() {
        renderBrowsePane(darkTheme = true, amoled = true)
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchRowLayout(rtl = false)
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_search_amoled.png")
    }

    /** RTL composer search-row baseline: matches start from the right edge. */
    @Test
    fun searchResultsRtl() {
        renderBrowsePane(darkTheme = true, amoled = false, layoutDirection = LayoutDirection.Rtl)
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchRowLayout(rtl = true)
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_search_dark_rtl.png")
    }

    /** Extra-large font search-row baseline: the field grows, the row keeps 48dp cells. */
    @Test
    fun searchResultsExtraLargeFont() {
        renderBrowsePane(
            darkTheme = false,
            amoled = false,
            appFontScale = AppFontScale.ExtraLarge.factor,
            osFontScale = REACTION_SHEET_OS_FONT_SCALE,
        )
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchRowLayout(rtl = false)
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_search_light_extra_large_font.png")
    }

    /** Renders reaction sheet browse pane. */
    private fun renderReactionSheetBrowsePane() {
        renderBrowsePane(
            darkTheme = false,
            amoled = false,
            height = REACTION_SHEET_HEIGHT,
            appFontScale = AppFontScale.ExtraLarge.factor,
            osFontScale = REACTION_SHEET_OS_FONT_SCALE,
        )
    }

    /** Renders browse pane. */
    private fun renderBrowsePane(
        darkTheme: Boolean,
        amoled: Boolean,
        width: Dp = PICKER_WIDTH,
        height: Dp = COMPOSER_BROWSE_HEIGHT,
        appFontScale: Float = 1f,
        osFontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            // The composer switches to the row once search takes focus and keeps it while the keyboard is up.
            var searchRow by remember { mutableStateOf(false) }
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = appFontScale) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale = osFontScale),
                    LocalLayoutDirection provides layoutDirection,
                    // Search baselines keep the field focused; a steady caret keeps them deterministic.
                    LocalCursorBlinkEnabled provides false,
                ) {
                    ComposerEmojiPickerPane(
                        height = height,
                        alpha = 1f,
                        recentEmojis = RecentEmojiPreferences.load(context),
                        onEmojiUsed = {},
                        onEmojiPicked = {},
                        onBackspace = {},
                        onSearchActiveChange = { if (it) searchRow = true },
                        searchRow = searchRow,
                        modifier = Modifier.width(width).testTag(PICKER_TAG),
                    )
                }
            }
        }
    }

    /** Waits until the browse grid is composed. */
    private fun waitForBrowseGrid() {
        repeat(100) {
            composeRule.waitForIdle()
            runCatching {
                composeRule.onNodeWithText(string(R.string.emoji_category_smileys_people)).assertIsDisplayed()
                composeRule.onNodeWithText(FIRST_SMILEYS_ROW.first()).assertIsDisplayed()
            }.onSuccess { return }
            Thread.sleep(20)
        }
        error("Emoji browse grid did not load")
    }

    /** Opens search (the composer's category bar icon, or a sheet's field) and types [query]. */
    private fun openSearchAndType(
        query: String,
        fromIcon: Boolean = true,
    ) {
        val opener = if (fromIcon) EMOJI_PICKER_SEARCH_OPEN_TEST_TAG else EMOJI_PICKER_SEARCH_TEST_TAG
        composeRule.onNodeWithTag(opener).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_TEST_TAG).performTextInput(query)
        repeat(100) {
            composeRule.waitForIdle()
            runCatching {
                composeRule
                    .onNodeWithTag(emojiPickerHeaderTestTag(EmojiCategory.Recent), useUnmergedTree = true)
                    .assertDoesNotExist()
                composeRule.onNodeWithText(FIRST_SEARCH_EMOJI).assertIsDisplayed()
            }.onSuccess {
                // The field keeps focus: losing it ends the composer's search row.
                composeRule.onNodeWithTag(EMOJI_PICKER_SEARCH_TEST_TAG).assertIsFocused()
                return
            }
            Thread.sleep(20)
        }
        error("Emoji search results did not load for query=$query")
    }

    /** Asserts browse grid layout. */
    private fun assertBrowseGridLayout() {
        composeRule.onNodeWithText(string(R.string.emoji_category_recent)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.emoji_category_smileys_people)).assertIsDisplayed()
        val header = boundsOfTag(emojiPickerHeaderTestTag(EmojiCategory.SmileysAndPeople))
        val firstCell = boundsOfTag(emojiPickerItemTestTag(EmojiCategory.SmileysAndPeople, 0))
        // The header's semantics sit inside its 8dp top gap, so the tagged node is the 36dp minimum minus that gap.
        val expectedHeaderContentHeight = (EXPECTED_HEADER_MIN_HEIGHT - EXPECTED_HEADER_TOP_GAP).value
        assertTrue(
            "Section headers reserve at least ${expectedHeaderContentHeight}px but measured ${header.height}px",
            header.height >= expectedHeaderContentHeight - GAP_TOLERANCE_PX,
        )
        assertVerticalGap(
            top = header.bottom,
            bottom = firstCell.top,
            expected = 0.dp,
            label = "Smileys header to first cell",
        )
        assertCellGrid(EmojiCategory.SmileysAndPeople)
        assertSameRow(FIRST_SMILEYS_ROW)
        assertStartsNextRow(FIRST_SMILEYS_ROW.last(), SECOND_SMILEYS_ROW.first())
    }

    /** Asserts search grid layout. */
    private fun assertSearchGridLayout(columns: Int) {
        composeRule
            .onNodeWithTag(emojiPickerHeaderTestTag(EmojiCategory.Recent), useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.emoji_category_smileys_people)).assertIsDisplayed()
        assertCellGrid(EmojiCategory.SmileysAndPeople, columns)
    }

    /** Matches sit on one row of 48dp cells below the field, in reading order, with no section headers. */
    private fun assertSearchRowLayout(rtl: Boolean) {
        composeRule
            .onNodeWithTag(emojiPickerHeaderTestTag(EmojiCategory.SmileysAndPeople), useUnmergedTree = true)
            .assertDoesNotExist()
        composeRule
            .onNodeWithTag(emojiPickerHeaderTestTag(EmojiCategory.Recent), useUnmergedTree = true)
            .assertDoesNotExist()
        val row = boundsOfTag(EMOJI_PICKER_SEARCH_ROW_TEST_TAG)
        val field = boundsOfTag(EMOJI_PICKER_SEARCH_TEST_TAG)
        assertEquals("Row height", EmojiSearchRowHeight.value, row.height, CELL_SIZE_TOLERANCE_PX)
        assertTrue(
            "Row ${row.top}px sits below the field ${field.bottom}px",
            row.top >= field.bottom - GAP_TOLERANCE_PX,
        )
        val cells =
            (0 until EXPECTED_COLUMN_COUNT).map {
                boundsOfTag(emojiPickerItemTestTag(EmojiCategory.SmileysAndPeople, it))
            }
        cells.forEach { cell ->
            assertEquals("Cell height", EXPECTED_CELL_SIZE.value, cell.height, CELL_SIZE_TOLERANCE_PX)
            assertEquals("Cell width", EXPECTED_CELL_SIZE.value, cell.width, CELL_SIZE_TOLERANCE_PX)
            assertEquals("Cell is centred in the row", row.center.y, cell.center.y, ROW_ALIGNMENT_TOLERANCE_PX)
        }
        cells.zipWithNext().forEach { (first, next) ->
            val step = if (rtl) first.left - next.left else next.left - first.left
            assertEquals("Adjacent cells are one cell apart", EXPECTED_CELL_SIZE.value, step, CELL_SIZE_TOLERANCE_PX)
        }
        val leadingInset = if (rtl) row.right - cells.first().right else cells.first().left - row.left
        assertEquals("Row starts at the screen margin", ROW_START_INSET.value, leadingInset, GAP_TOLERANCE_PX)
    }

    /** Cells are 48dp squares, [columns] per row, with the next row flush below the first. */
    private fun assertCellGrid(
        category: EmojiCategory,
        columns: Int = EXPECTED_COLUMN_COUNT,
    ) {
        val cells = (0..columns).map { boundsOfTag(emojiPickerItemTestTag(category, it)) }
        cells.forEach { cell ->
            assertEquals("Cell height", EXPECTED_CELL_SIZE.value, cell.height, CELL_SIZE_TOLERANCE_PX)
            assertEquals("Cell width", EXPECTED_CELL_SIZE.value, cell.width, CELL_SIZE_TOLERANCE_PX)
        }
        val firstRow = cells.take(columns)
        assertTrue(
            "Expected $columns cells on the first row but tops were ${firstRow.map { it.top }}",
            firstRow.all { abs(it.top - firstRow.first().top) <= ROW_ALIGNMENT_TOLERANCE_PX },
        )
        val nextRowFirst = cells.last()
        assertVerticalGap(
            top = firstRow.first().bottom,
            bottom = nextRowFirst.top,
            expected = EXPECTED_ROW_SPACING,
            label = "Grid row spacing",
        )
        assertEquals(
            "The next row starts at the leading column",
            firstRow.first().left,
            nextRowFirst.left,
            ROW_ALIGNMENT_TOLERANCE_PX,
        )
    }

    private fun assertSameRow(emojis: List<String>) {
        val tops = emojis.map { boundsOfText(it).top }
        val minTop = tops.min()
        val maxTop = tops.max()
        assertTrue(
            "Expected $EXPECTED_COLUMN_COUNT emojis on one row but tops ranged $minTop..$maxTop for $emojis",
            maxTop - minTop <= ROW_ALIGNMENT_TOLERANCE_PX,
        )
    }

    private fun assertStartsNextRow(
        lastInRow: String,
        firstInNextRow: String,
    ) {
        val rowBottom = boundsOfText(lastInRow).bottom
        val nextRowTop = boundsOfText(firstInNextRow).top
        assertTrue(
            "Expected $firstInNextRow to start the next row after $lastInRow " +
                "but measured tops ${rowBottom}px vs ${nextRowTop}px",
            nextRowTop >= rowBottom - ROW_ALIGNMENT_TOLERANCE_PX,
        )
    }

    private fun assertNoHorizontalOverlap(emojis: List<String>) {
        val bounds = emojis.map { boundsOfText(it) }
        for (index in bounds.indices) {
            for (otherIndex in index + 1 until bounds.size) {
                val left = bounds[index]
                val right = bounds[otherIndex]
                val overlapsHorizontally =
                    left.right > right.left + OVERLAP_TOLERANCE_PX &&
                        right.right > left.left + OVERLAP_TOLERANCE_PX
                assertTrue(
                    "Expected no horizontal overlap between ${emojis[index]} and ${emojis[otherIndex]} " +
                        "but bounds were $left and $right",
                    !overlapsHorizontally,
                )
            }
        }
    }

    private fun assertVerticalGap(
        top: Float,
        bottom: Float,
        expected: Dp,
        label: String,
    ) {
        val gapPx = bottom - top
        // Robolectric config uses mdpi, so 1.dp == 1px for these assertions.
        val expectedPx = expected.value
        assertTrue(
            "$label expected ${expectedPx}px but measured ${gapPx}px",
            abs(gapPx - expectedPx) <= GAP_TOLERANCE_PX,
        )
    }

    /** Bounds of text. */
    private fun boundsOfText(text: String): Rect {
        val bounds =
            composeRule
                .onNodeWithText(text, substring = false, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        // Robolectric config uses mdpi, so 1.dp == 1px for these assertions.
        return Rect(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)
    }

    /** Bounds of tag. */
    private fun boundsOfTag(tag: String): Rect {
        val bounds =
            composeRule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        return Rect(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)
    }

    /** Text layout result. */
    private fun textLayoutResult(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, substring = false, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
        return results.single()
    }

    private companion object {
        val PICKER_WIDTH = 360.dp

        // The composer's browsing pane on the 780dp test window, keyboard at the fallback height.
        val COMPOSER_BROWSE_HEIGHT = ComposerEmojiPickerFallbackHeight

        // The reaction sheet fills 0.88 of the screen; enough rows for the large-font wrap checks.
        val REACTION_SHEET_HEIGHT = 640.dp
        const val REACTION_SHEET_OS_FONT_SCALE = 1.5f
        const val PICKER_TAG = "emoji-picker-pane"

        // 360dp minus the composer grid's 8dp side insets leaves 344dp: seven adaptive 48dp columns.
        const val EXPECTED_COLUMN_COUNT = 7

        // The reaction sheet keeps its 16dp insets: 328dp holds six columns.
        const val SHEET_COLUMN_COUNT = 6
        val EXPECTED_ROW_SPACING = 0.dp
        val EXPECTED_CELL_SIZE = EmojiPickerMinimumCellSize
        val EXPECTED_HEADER_MIN_HEIGHT = 36.dp
        val EXPECTED_HEADER_TOP_GAP = 8.dp
        val ROW_START_INSET = 8.dp
        const val ROW_ALIGNMENT_TOLERANCE_PX = 2f
        const val GAP_TOLERANCE_PX = 2f
        const val OVERLAP_TOLERANCE_PX = 1f
        const val CELL_SIZE_TOLERANCE_PX = 1f
        const val EMOJI_GLYPH_FILL_FRACTION = EMOJI_PICKER_CELL_GLYPH_FILL_FRACTION

        val FIRST_SMILEYS_ROW = listOf("😀", "😃", "😄", "😁", "😆", "😅", "🤣")
        val SECOND_SMILEYS_ROW = listOf("😂", "🙂", "🙃", "🫠", "😉", "😊", "😇")
        const val FIRST_SEARCH_EMOJI = "😀"
    }
}
