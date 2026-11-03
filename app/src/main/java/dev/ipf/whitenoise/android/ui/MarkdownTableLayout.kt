package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/** Preferred separation between adjacent table columns — a host too narrow for it gets a smaller gap. */
internal val MARKDOWN_TABLE_COLUMN_GAP = 8.dp

/** Vertical spacing between table rows, row rules and the row-elision marker. */
internal val MARKDOWN_TABLE_ROW_SPACING = 4.dp

/**
 * The visible table window as the layout walks it, header first: each row's rendered cell count and
 * whether it ends in a cell-elision marker. Every elided row puts its marker in one shared trailing
 * column, so a short body row and a capped header keep the same column model.
 */
internal data class MarkdownTableShape(
    val rowCellCounts: List<Int>,
    val rowCellsElided: List<Boolean>,
) {
    /** Content columns, which is the widest visible row, shorter rows leave their trailing columns empty. */
    val columnCount: Int = rowCellCounts.maxOrNull() ?: 0

    /** Whether any row needs the trailing elision column. */
    val hasElisionColumn: Boolean = rowCellsElided.any { it }

    /** Columns that receive a width — content columns plus the elision column when present. */
    val trackCount: Int = columnCount + if (hasElisionColumn) 1 else 0
}

/** One width vector shared by the header and every body row, plus the effective gap between columns. */
internal data class MarkdownTableColumnWidths(
    val widths: List<Int>,
    val gap: Int,
) {
    /** Horizontal extent of the allocated columns and the gaps between them. */
    val totalWidth: Int = widths.sum() + gap * (widths.size - 1).coerceAtLeast(0)

    /** Start offset of each column measured from the table's start edge. */
    fun columnStarts(): List<Int> {
        var x = 0
        return widths.map { width ->
            val start = x
            x += width + gap
            start
        }
    }
}

/**
 * Allocates [availableWidth] among columns whose single-line content needs [demands] pixels.
 *
 * Gaps come off first, and never take more than half the width, so a narrow host still leaves the
 * cells room. Each round, a column whose demand fits an equal share of the remaining budget gets
 * exactly its demand, and its unused share returns to the budget for the rest. When no remaining
 * column fits, they split what is left evenly and wrap. Each round settles at least one column, so
 * the work is bounded by the column count, and the result never exceeds [availableWidth].
 */
internal fun markdownTableColumnWidths(
    demands: List<Int>,
    availableWidth: Int,
    preferredGap: Int,
): MarkdownTableColumnWidths {
    val count = demands.size
    if (count == 0) return MarkdownTableColumnWidths(emptyList(), 0)
    val available = availableWidth.coerceAtLeast(0)
    val gapCount = count - 1
    val gap = if (gapCount == 0) 0 else minOf(preferredGap.coerceAtLeast(0), available / 2 / gapCount)
    val widths = IntArray(count)
    var remaining = available - gap * gapCount
    val open = (0 until count).toMutableList()
    while (open.isNotEmpty()) {
        val share = remaining / open.size
        val fitting = open.filter { demands[it].coerceAtLeast(0) <= share }
        if (fitting.isEmpty()) {
            // Every remaining column needs more than an equal share: split evenly, leading columns take
            // the leftover pixels so the total stays exact.
            val leftover = remaining - share * open.size
            open.forEachIndexed { index, column -> widths[column] = share + if (index < leftover) 1 else 0 }
            break
        }
        fitting.forEach { column ->
            widths[column] = demands[column].coerceAtLeast(0)
            remaining -= widths[column]
        }
        open.removeAll(fitting)
    }
    return MarkdownTableColumnWidths(widths.toList(), gap)
}

/**
 * Lays out a Markdown table's visible window with one shared column allocation.
 *
 * Each slot emits one layout node per item, in [shape] order: [cells] row-major, header first,
 * [rowMarkers] for each row that elides cells, [dividers] between adjacent rows and [tableMarker]
 * for elided rows. Column demand is each visible cell's single-line intrinsic width, so only the
 * bounded window is measured and the same input always yields the same widths.
 */
@Suppress("FunctionNaming")
@Composable
internal fun MarkdownTableLayout(
    shape: MarkdownTableShape,
    cells: @Composable () -> Unit,
    rowMarkers: @Composable () -> Unit,
    dividers: @Composable () -> Unit,
    tableMarker: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val policy = remember(shape) { MarkdownTableMeasurePolicy(shape) }
    Layout(
        contents = listOf(cells, rowMarkers, dividers, tableMarker),
        modifier = modifier,
        measurePolicy = policy,
    )
}

private const val CELLS_SLOT = 0
private const val ROW_MARKERS_SLOT = 1
private const val DIVIDERS_SLOT = 2
private const val TABLE_MARKER_SLOT = 3

/** Measures every row against the shared [MarkdownTableColumnWidths] and stacks rows with their rules. */
private class MarkdownTableMeasurePolicy(
    private val shape: MarkdownTableShape,
) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<List<Measurable>>,
        constraints: Constraints,
    ): MeasureResult {
        val cells = measurables[CELLS_SLOT]
        val rowMarkers = measurables[ROW_MARKERS_SLOT]
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val demands = columnDemands(cells, rowMarkers)
        val allocation = markdownTableColumnWidths(demands, available, MARKDOWN_TABLE_COLUMN_GAP.roundToPx())
        val rows = measureRows(cells, rowMarkers, allocation)
        val tableWidth = allocation.totalWidth
        val dividerPlaceables = measurables[DIVIDERS_SLOT].map { it.measure(Constraints.fixedWidth(tableWidth)) }
        val markerConstraints = Constraints(maxWidth = available.coerceAtLeast(tableWidth))
        val markerPlaceable = measurables[TABLE_MARKER_SLOT].firstOrNull()?.measure(markerConstraints)
        val spacing = MARKDOWN_TABLE_ROW_SPACING.roundToPx()
        val items = ArrayList<Pair<List<Pair<Int, Placeable>>, Int>>()
        rows.forEachIndexed { index, row ->
            items += row to row.maxOfOrNull { it.second.height }.orZero()
            dividerPlaceables.getOrNull(index)?.let { items += listOf(0 to it) to it.height }
        }
        markerPlaceable?.let { items += listOf(0 to it) to it.height }
        val contentHeight = items.sumOf { it.second } + spacing * (items.size - 1).coerceAtLeast(0)
        val width = constraints.constrainWidth(maxOf(tableWidth, markerPlaceable?.width.orZero()))
        return layout(width, constraints.constrainHeight(contentHeight)) {
            var y = 0
            items.forEach { (placeables, height) ->
                placeables.forEach { (x, placeable) -> placeable.placeRelative(x, y) }
                y += height + spacing
            }
        }
    }

    /** Widest single-line demand per column across the header and every visible body row. */
    private fun columnDemands(
        cells: List<Measurable>,
        rowMarkers: List<Measurable>,
    ): List<Int> {
        val demands = IntArray(shape.trackCount)
        var cellIndex = 0
        var markerIndex = 0
        shape.rowCellCounts.forEachIndexed { row, count ->
            repeat(count) { column ->
                val demand = cells[cellIndex++].maxIntrinsicWidth(Constraints.Infinity)
                demands[column] = maxOf(demands[column], demand)
            }
            if (shape.rowCellsElided[row]) {
                val demand = rowMarkers[markerIndex++].maxIntrinsicWidth(Constraints.Infinity)
                demands[shape.columnCount] = maxOf(demands[shape.columnCount], demand)
            }
        }
        return demands.toList()
    }

    /** Measures each row's cells and elision marker at their column's fixed width, keyed by start offset. */
    private fun measureRows(
        cells: List<Measurable>,
        rowMarkers: List<Measurable>,
        allocation: MarkdownTableColumnWidths,
    ): List<List<Pair<Int, Placeable>>> {
        val starts = allocation.columnStarts()
        var cellIndex = 0
        var markerIndex = 0
        return shape.rowCellCounts.mapIndexed { row, count ->
            val placed = ArrayList<Pair<Int, Placeable>>(count + 1)
            repeat(count) { column ->
                val cell = cells[cellIndex++].measure(Constraints.fixedWidth(allocation.widths[column]))
                placed += starts[column] to cell
            }
            if (shape.rowCellsElided[row]) {
                val column = shape.columnCount
                placed += starts[column] to
                    rowMarkers[markerIndex++].measure(
                        Constraints.fixedWidth(allocation.widths[column]),
                    )
            }
            placed
        }
    }

    /** Treats an absent measurement as zero extent. */
    private fun Int?.orZero(): Int = this ?: 0
}
