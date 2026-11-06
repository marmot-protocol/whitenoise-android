package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded content-demand allocation behind every Markdown table's shared column widths (#3562). */
class MarkdownTableColumnSizingTest {
    /** A short label column keeps its text width and the paragraph column takes everything left. */
    @Test
    fun shortColumnKeepsItsDemandAndTheLongColumnTakesTheRest() {
        val result = markdownTableColumnWidths(listOf(20, 900), availableWidth = 300, preferredGap = 8)

        assertEquals(listOf(20, 272), result.widths)
        assertEquals(300, result.totalWidth)
    }

    /** Columns that all fit keep their exact demands rather than stretching to the host width. */
    @Test
    fun fittingColumnsKeepTheirDemandsWithoutStretching() {
        val result = markdownTableColumnWidths(listOf(40, 60, 30), availableWidth = 500, preferredGap = 8)

        assertEquals(listOf(40, 60, 30), result.widths)
        assertEquals(8, result.gap)
        assertEquals(146, result.totalWidth)
    }

    /** Equally demanding columns split the budget evenly and the leftover pixels keep the total exact. */
    @Test
    fun balancedOverflowingColumnsSplitTheBudgetEvenly() {
        val result = markdownTableColumnWidths(listOf(500, 500, 500), availableWidth = 317, preferredGap = 8)

        assertEquals(listOf(101, 100, 100), result.widths)
        assertEquals(317, result.totalWidth)
    }

    /** A column that fits only after a short column releases its share is satisfied in a later round. */
    @Test
    fun releasedShareSatisfiesAMediumColumnInALaterRound() {
        // Budget 300: an equal share of 100 satisfies only the 10 px column, the medium column then
        // fits its 145 px share and the long column receives the remaining 150 px.
        val result = markdownTableColumnWidths(listOf(10, 140, 900), availableWidth = 316, preferredGap = 8)

        assertEquals(listOf(10, 140, 150), result.widths)
        assertEquals(316, result.totalWidth)
    }

    /** Gaps never take more than half a narrow host, so the cells always keep room to draw. */
    @Test
    fun gapsShrinkWhenTheyWouldExhaustANarrowWidth() {
        val demands = List(MARKDOWN_MAX_TABLE_COLUMNS + 1) { 50 }
        val result = markdownTableColumnWidths(demands, availableWidth = 48, preferredGap = 8)

        assertEquals(2, result.gap)
        assertTrue(result.totalWidth <= 48)
        assertTrue(result.widths.all { it >= 0 })
        assertEquals(24, result.widths.sum())
    }

    /** Zero, negative or empty inputs still produce nonnegative widths inside the host. */
    @Test
    fun degenerateInputsStayNonnegativeAndBounded() {
        assertEquals(emptyList<Int>(), markdownTableColumnWidths(emptyList(), 100, 8).widths)
        val zeroWidth = markdownTableColumnWidths(listOf(10, 20), availableWidth = 0, preferredGap = 8)
        assertEquals(listOf(0, 0), zeroWidth.widths)
        assertEquals(0, zeroWidth.totalWidth)
        val negative = markdownTableColumnWidths(listOf(-5, 30), availableWidth = 100, preferredGap = -3)
        assertEquals(listOf(0, 30), negative.widths)
        assertEquals(0, negative.gap)
    }

    /** One column ignores the gap entirely and is clamped to the host. */
    @Test
    fun singleColumnHasNoGapAndIsClampedToTheHost() {
        val result = markdownTableColumnWidths(listOf(1_000), availableWidth = 240, preferredGap = 8)

        assertEquals(listOf(240), result.widths)
        assertEquals(0, result.gap)
    }

    /** Any demand mix at the visible column cap fits the host and gives no column more than it asked for. */
    @Test
    fun everyAllocationAtTheColumnCapIsBoundedByHostAndDemand() {
        val random = java.util.Random(3562)
        repeat(500) {
            val count = 1 + random.nextInt(MARKDOWN_MAX_TABLE_COLUMNS + 1)
            val demands = List(count) { random.nextInt(2_000) }
            val available = random.nextInt(1_200)
            val result = markdownTableColumnWidths(demands, available, preferredGap = 8)

            assertTrue(result.totalWidth <= available)
            result.widths.forEachIndexed { column, width ->
                assertTrue(width in 0..maxOf(demands[column], 0))
            }
        }
    }

    /** Column starts include each preceding width plus one effective gap. */
    @Test
    fun columnStartsFollowWidthsAndGap() {
        assertEquals(listOf(0, 28, 58), MarkdownTableColumnWidths(listOf(20, 22, 40), gap = 8).columnStarts())
    }

    /** The shape keeps one trailing elision column whenever any row drops cells. */
    @Test
    fun shapeReservesOneSharedElisionColumn() {
        val shape = MarkdownTableShape(listOf(3, 1, 2), listOf(false, true, false))

        assertEquals(3, shape.columnCount)
        assertTrue(shape.hasElisionColumn)
        assertEquals(4, shape.trackCount)
    }
}
