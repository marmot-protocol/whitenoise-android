package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Laid-out spacing for authored blank runs (#1719). The pure count helper can't
 * see the arrangement, so these measure real gaps between rendered blocks — the
 * spacer is an arranged child, and getting its height wrong makes the gap grow
 * non-linearly with the authored blank count.
 *
 * Every case rides one composition: `setContent` may only be called once per
 * test, so the fixture renders a document whose blocks carry an increasing blank
 * run and each test reads the gaps it cares about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkdownBlankLineLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val labels = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta")

    // Blank source lines before each block. Index 0 is leading whitespace the
    // send path already normalizes; the last entry is past the render ceiling.
    private val blankRuns = byteArrayOf(0, 1, 2, 3, 4, 120)

    /** Gap in dp between the bottom of [labels] `[from]` and the top of `[from + 1]`. */
    private fun gapsDp(): List<Float> {
        composeRule.setContent {
            WhiteNoiseTheme {
                MarkdownMessageBody(
                    MarkdownDocumentFfi(
                        blocks = labels.map { MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(it))) },
                        truncated = false,
                        blankLinesBefore = blankRuns,
                    ),
                )
            }
        }
        composeRule.waitForIdle()
        val bounds = labels.map { composeRule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        return with(composeRule.density) {
            bounds.zipWithNext { above, below -> (below.top - above.bottom).toDp().value }
        }
    }

    @Test
    fun oneBlankLineRendersTheOrdinaryBlockGap() {
        // alpha → beta carries a single authored blank line.
        assertEquals(MARKDOWN_BLOCK_SPACING.value, gapsDp()[0], 1f)
    }

    @Test
    fun twoBlankLinesAddExactlyOneBlankLineHeight() {
        // beta → gamma carries two.
        assertEquals(
            MARKDOWN_BLOCK_SPACING.value + MARKDOWN_BLANK_LINE_HEIGHT.value,
            gapsDp()[1],
            1f,
        )
    }

    @Test
    fun eachFurtherBlankLineGrowsTheGapByTheSameAmount() {
        val gaps = gapsDp()

        // The first extra line must not cost more than the ones after it — the
        // spacer's own arrangement gap made this non-linear before.
        assertEquals(MARKDOWN_BLANK_LINE_HEIGHT.value, gaps[1] - gaps[0], 1f)
        assertEquals(MARKDOWN_BLANK_LINE_HEIGHT.value, gaps[2] - gaps[1], 1f)
        assertEquals(MARKDOWN_BLANK_LINE_HEIGHT.value, gaps[3] - gaps[2], 1f)
    }

    @Test
    fun hostileBlankRunIsBoundedAndStillMonotonic() {
        val gaps = gapsDp()
        val bounded = gaps.last()

        assertTrue("a larger blank run must not shrink the gap", bounded > gaps.first())
        assertEquals(
            MARKDOWN_BLOCK_SPACING.value + MARKDOWN_BLANK_LINE_HEIGHT.value * MARKDOWN_MAX_EXTRA_BLANK_LINES,
            bounded,
            1f,
        )
    }

    @Test
    fun spacerHeightCompensatesForItsOwnArrangementGap() {
        // One extra line grows the gap by one blank-line height in total, so the
        // spacer is shorter by the arrangement gap that inserting it introduces.
        assertEquals(
            MARKDOWN_BLANK_LINE_HEIGHT - MARKDOWN_BLOCK_SPACING,
            markdownBlankRunSpacerHeight(1),
        )
        assertEquals(0.dp, markdownBlankRunSpacerHeight(0))
    }
}
