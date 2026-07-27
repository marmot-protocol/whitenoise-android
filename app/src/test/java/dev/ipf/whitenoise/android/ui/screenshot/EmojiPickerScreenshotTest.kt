package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppFontScale
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerEmojiPickerFallbackHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerEmojiPickerPane
import dev.ipf.whitenoise.android.ui.conversation.composer.EMOJI_PICKER_CELL_GLYPH_FILL_FRACTION
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiPickerCellTextMetrics
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
 * Regression guard for issue #1665. The shared emoji-picker content must use a
 * denser 10-column grid, 2.dp row spacing, and tighter section-header padding
 * in both browse (Recent + Smileys) and search modes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class EmojiPickerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int): String = context.getString(resId)

    @Before
    fun seedRecentEmojis() {
        context
            .getSharedPreferences("whitenoise_ui", Context.MODE_PRIVATE)
            .edit()
            .putString("recent_reaction_emojis", "🔥\n❤️\n👍")
            .apply()
    }

    @Test
    fun browseGridUsesTenColumnsWithTighterSpacingAndHeaderPadding() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        assertBrowseGridLayout()
    }

    @Test
    fun searchGridUsesTenColumnsWithTighterSpacing() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchGridLayout()
    }

    @Test
    fun reactionSheetCellGlyphsDoNotOverlapAtExtraLargeFontScale() {
        renderReactionSheetBrowsePane()
        waitForBrowseGrid()

        val appliedLayout = textLayoutResult(FIRST_SMILEYS_ROW.first())
        val cellSizePx =
            appliedLayout.layoutInput.constraints.maxWidth
                .toFloat()
        val typography = Typography.scaledBy(AppFontScale.ExtraLarge.factor)
        val uncappedLineHeightPx = typography.headlineMedium.lineHeight.value * REACTION_SHEET_OS_FONT_SCALE
        assertTrue(
            "Uncapped emoji line height would overflow a reaction-sheet cell",
            uncappedLineHeightPx > cellSizePx,
        )

        val (fontSize, lineHeight) =
            emojiPickerCellTextMetrics(
                cellSizeDp = cellSizePx.dp,
                baseStyle = typography.headlineMedium,
                densityFontScale = REACTION_SHEET_OS_FONT_SCALE,
            )
        val cappedLineHeightPx = lineHeight.value * REACTION_SHEET_OS_FONT_SCALE
        assertTrue(
            "Expected capped line height ${cappedLineHeightPx}px to fit " +
                "${cellSizePx * EMOJI_GLYPH_FILL_FRACTION}px cell content",
            cappedLineHeightPx <= cellSizePx * EMOJI_GLYPH_FILL_FRACTION + CELL_SIZE_TOLERANCE_PX,
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

    @Test
    fun browseRecentAndSmileysLight() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        assertBrowseGridLayout()
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_browse_light.png")
    }

    @Test
    fun browseRecentAndSmileysDark() {
        renderBrowsePane(darkTheme = true, amoled = false)
        waitForBrowseGrid()
        assertBrowseGridLayout()
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_browse_dark.png")
    }

    @Test
    fun browseRecentAndSmileysAmoled() {
        renderBrowsePane(darkTheme = true, amoled = true)
        waitForBrowseGrid()
        assertBrowseGridLayout()
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_browse_amoled.png")
    }

    @Test
    fun searchResultsLight() {
        renderBrowsePane(darkTheme = false, amoled = false)
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchGridLayout()
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_search_light.png")
    }

    @Test
    fun searchResultsDark() {
        renderBrowsePane(darkTheme = true, amoled = false)
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchGridLayout()
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_search_dark.png")
    }

    @Test
    fun searchResultsAmoled() {
        renderBrowsePane(darkTheme = true, amoled = true)
        waitForBrowseGrid()
        openSearchAndType("happy")
        assertSearchGridLayout()
        composeRule
            .onNodeWithTag(PICKER_TAG)
            .captureRoboImage("src/test/snapshots/emoji_picker_search_amoled.png")
    }

    private fun renderBrowsePane(
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        renderBrowsePane(darkTheme = darkTheme, amoled = amoled, width = PICKER_WIDTH)
    }

    private fun renderReactionSheetBrowsePane() {
        renderBrowsePane(
            darkTheme = false,
            amoled = false,
            width = REACTION_SHEET_PICKER_WIDTH,
            appFontScale = AppFontScale.ExtraLarge.factor,
            osFontScale = REACTION_SHEET_OS_FONT_SCALE,
        )
    }

    private fun renderBrowsePane(
        darkTheme: Boolean,
        amoled: Boolean,
        width: Dp = PICKER_WIDTH,
        appFontScale: Float = 1f,
        osFontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = appFontScale) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale = osFontScale),
                ) {
                    ComposerEmojiPickerPane(
                        height = ComposerEmojiPickerFallbackHeight,
                        alpha = 1f,
                        onEmojiPicked = {},
                        onBackspace = {},
                        onSearchActiveChange = {},
                        modifier = Modifier.width(width).testTag(PICKER_TAG),
                    )
                }
            }
        }
    }

    private fun waitForBrowseGrid() {
        repeat(100) {
            composeRule.waitForIdle()
            runCatching {
                composeRule.onNodeWithText(string(R.string.emoji_category_smileys)).assertIsDisplayed()
                composeRule.onNodeWithText(FIRST_SMILEYS_ROW.first()).assertIsDisplayed()
            }.onSuccess { return }
            Thread.sleep(20)
        }
        error("Emoji browse grid did not load")
    }

    private fun openSearchAndType(query: String) {
        composeRule
            .onNodeWithContentDescription(string(R.string.emoji_search_hint))
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextInput(query)
        repeat(100) {
            composeRule.waitForIdle()
            runCatching {
                composeRule.onNodeWithText(FIRST_SEARCH_ROW.first()).assertIsDisplayed()
            }.onSuccess { return }
            Thread.sleep(20)
        }
        error("Emoji search results did not load for query=$query")
    }

    private fun assertBrowseGridLayout() {
        composeRule.onNodeWithText(string(R.string.emoji_category_recent)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.emoji_category_smileys)).assertIsDisplayed()

        assertSameRow(FIRST_SMILEYS_ROW)
        assertStartsNextRow(FIRST_SMILEYS_ROW.last(), SECOND_SMILEYS_ROW.first())

        val smileysHeader = boundsOfText(string(R.string.emoji_category_smileys))
        val firstSmiley = boundsOfText(FIRST_SMILEYS_ROW.first())
        assertVerticalGap(
            top = smileysHeader.bottom,
            bottom = firstSmiley.top,
            expected = EXPECTED_SECTION_HEADER_PADDING,
            label = "Smileys section header bottom padding",
        )

        val row1Bottom = boundsOfText(FIRST_SMILEYS_ROW.last()).bottom
        val row2Top = boundsOfText(SECOND_SMILEYS_ROW.first()).top
        assertVerticalGap(
            top = row1Bottom,
            bottom = row2Top,
            expected = EXPECTED_ROW_SPACING,
            label = "Browse grid row spacing",
        )
    }

    private fun assertSearchGridLayout() {
        assertSameRow(FIRST_SEARCH_ROW)
        assertStartsNextRow(FIRST_SEARCH_ROW.last(), SECOND_SEARCH_ROW.first())

        val row1Bottom = boundsOfText(FIRST_SEARCH_ROW.last()).bottom
        val row2Top = boundsOfText(SECOND_SEARCH_ROW.first()).top
        assertVerticalGap(
            top = row1Bottom,
            bottom = row2Top,
            expected = EXPECTED_ROW_SPACING,
            label = "Search grid row spacing",
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

    private fun boundsOfText(text: String): Rect {
        val bounds =
            composeRule
                .onNodeWithText(text, substring = false, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        // Robolectric config uses mdpi, so 1.dp == 1px for these assertions.
        return Rect(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)
    }

    private fun textLayoutResult(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(text, substring = false, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
        return results.single()
    }

    private companion object {
        val PICKER_WIDTH = 360.dp

        // Matches a 360dp reaction sheet after its 18dp horizontal padding.
        val REACTION_SHEET_PICKER_WIDTH = 348.dp
        const val REACTION_SHEET_OS_FONT_SCALE = 1.5f
        const val PICKER_TAG = "emoji-picker-pane"
        const val EXPECTED_COLUMN_COUNT = 10
        val EXPECTED_ROW_SPACING = 2.dp
        val EXPECTED_SECTION_HEADER_PADDING = 4.dp
        const val ROW_ALIGNMENT_TOLERANCE_PX = 2f
        const val GAP_TOLERANCE_PX = 2f
        const val OVERLAP_TOLERANCE_PX = 1f
        const val CELL_SIZE_TOLERANCE_PX = 1f
        const val EMOJI_GLYPH_FILL_FRACTION = EMOJI_PICKER_CELL_GLYPH_FILL_FRACTION

        val FIRST_SMILEYS_ROW =
            listOf("😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃")
        val SECOND_SMILEYS_ROW =
            listOf("🫠", "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "☺️")

        val FIRST_SEARCH_ROW =
            listOf("😀", "😃", "😄", "😁", "😆", "🤣", "😂", "🙂", "😇", "☺️")
        val SECOND_SEARCH_ROW =
            listOf("🥲", "🥳", "🤸", "🎂", "😐", "😑", "😒", "😧", "😢", "😭")
    }
}
