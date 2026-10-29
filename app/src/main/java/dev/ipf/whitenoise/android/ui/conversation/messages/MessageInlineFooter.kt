@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha

/** Legibility scrim for a footer overlaid on visual media (image/video). */
@Composable
private fun MediaScrimFooter(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(Color.Black.copy(alpha = ScrimAlpha.CHIP), RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        content()
    }
}

/** Time (+ outgoing status) overlaid on the bottom-right of a visual-media bubble. */
@Composable
internal fun BoxScope.MediaFooterOverlay(
    timeText: String,
    showStatus: Boolean,
    status: MessageStatus,
) {
    MediaScrimFooter(
        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
    ) {
        MessageInlineFooter(
            timeText = timeText,
            color = Color.White,
            showStatus = showStatus,
            status = status,
            editedLabel = null,
            onEditedClick = null,
            statusContainerColor = Color.Black,
        )
    }
}

/** Bottom-end edited, timestamp, and outgoing-status chrome. */
@Composable
internal fun MessageInlineFooter(
    timeText: String,
    color: Color,
    showStatus: Boolean,
    status: MessageStatus,
    editedLabel: String?,
    onEditedClick: (() -> Unit)?,
    showTime: Boolean = true,
    modifier: Modifier = Modifier,
    statusContainerColor: Color? = null,
) {
    val baselineIndex = footerBaselineIndex(showTime, editedLabel != null, showStatus)
    Layout(
        modifier = modifier,
        content = {
            MessageInlineFooterItems(
                timeText = timeText,
                color = color,
                showStatus = showStatus,
                status = status,
                editedLabel = editedLabel,
                onEditedClick = onEditedClick,
                showTime = showTime,
                statusContainerColor = statusContainerColor,
            )
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gap = FooterItemSpacing.roundToPx()
        val rows = footerItemRows(placeables.map { it.width }, constraints.maxWidth, gap)
        val rowWidths = rows.map { indices -> indices.sumOf { placeables[it].width } + gap * (indices.size - 1) }
        val rowHeights = rows.map { indices -> indices.maxOf { placeables[it].height } }
        val width = (rowWidths.maxOrNull() ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = rowHeights.sum().coerceIn(constraints.minHeight, constraints.maxHeight)
        val alignmentLines = footerAlignmentLines(placeables, rows, rowHeights, baselineIndex)
        layout(width, height, alignmentLines) {
            var y = 0
            rows.forEachIndexed { rowIndex, indices ->
                var x = width - rowWidths[rowIndex]
                indices.forEach { index ->
                    val placeable = placeables[index]
                    placeable.placeRelative(x, y + (rowHeights[rowIndex] - placeable.height) / 2)
                    x += placeable.width + gap
                }
                y += rowHeights[rowIndex]
            }
        }
    }
}

/** Partition bounded metadata from the timestamp backwards, preserving reading order within rows. */
internal fun footerItemRows(
    widths: List<Int>,
    maxWidth: Int,
    gap: Int,
): List<List<Int>> {
    val rows = mutableListOf<List<Int>>()
    var row = mutableListOf<Int>()
    var rowWidth = 0
    widths.indices.reversed().forEach { index ->
        val required = widths[index] + if (row.isEmpty()) 0 else gap
        if (row.isNotEmpty() && rowWidth + required > maxWidth) {
            rows.add(0, row)
            row = mutableListOf()
            rowWidth = 0
        }
        row.add(0, index)
        rowWidth += widths[index] + if (row.size == 1) 0 else gap
    }
    if (row.isNotEmpty()) rows.add(0, row)
    return rows
}

private fun footerAlignmentLines(
    placeables: List<Placeable>,
    rows: List<List<Int>>,
    rowHeights: List<Int>,
    baselineIndex: Int?,
): Map<AlignmentLine, Int> {
    val baselinePlaceable = baselineIndex?.let(placeables::get)
    val baselineRow = rows.indexOfFirst { baselineIndex != null && baselineIndex in it }
    val baselineY =
        if (baselineRow >= 0 && baselinePlaceable != null) {
            rowHeights.take(baselineRow).sum() + (rowHeights[baselineRow] - baselinePlaceable.height) / 2
        } else {
            null
        }
    val baseline =
        baselinePlaceable?.get(LastBaseline)?.takeIf { it != AlignmentLine.Unspecified }
            ?: baselinePlaceable?.get(FirstBaseline)
            ?: AlignmentLine.Unspecified
    return if (baseline == AlignmentLine.Unspecified || baselineY == null) {
        emptyMap()
    } else {
        val firstRowBaseline =
            rows.first().firstNotNullOfOrNull { index ->
                val itemBaseline = placeables[index][FirstBaseline]
                itemBaseline.takeIf { it != AlignmentLine.Unspecified }?.let {
                    (rowHeights.first() - placeables[index].height) / 2 + it
                }
            } ?: (baselineY + baseline)
        mapOf<AlignmentLine, Int>(
            FirstBaseline to firstRowBaseline,
            LastBaseline to baselineY + baseline,
        )
    }
}

/**
 * Footer items in prototype order: the edited label, outgoing delivery glyph, and timestamp.
 */
@Suppress("FunctionNaming")
@Composable
private fun MessageInlineFooterItems(
    timeText: String,
    color: Color,
    showStatus: Boolean,
    status: MessageStatus,
    editedLabel: String?,
    onEditedClick: (() -> Unit)?,
    showTime: Boolean,
    statusContainerColor: Color?,
) {
    editedLabel?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = if (onEditedClick != null) Modifier.clickable(onClick = onEditedClick) else Modifier,
        )
    }
    if (showStatus) {
        OutgoingMessageStatusIcon(status, tint = color, containerColor = statusContainerColor)
    }
    if (showTime) {
        Text(timeText, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** The time carries the baseline; it follows the edited label and delivery glyph. */
private fun footerBaselineIndex(
    showTime: Boolean,
    hasEditedLabel: Boolean,
    showStatus: Boolean,
): Int? =
    when {
        showTime -> (if (hasEditedLabel) 1 else 0) + (if (showStatus) 1 else 0)
        hasEditedLabel -> 0
        else -> null
    }

private val FooterItemSpacing = 3.dp
