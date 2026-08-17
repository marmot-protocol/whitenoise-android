package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Gap between a bubble's text and its trailing inline footer.
private val BubbleFooterGap = 8.dp

/**
 * Lays [content] with [footer] pinned bottom-end. The footer joins the last
 * line when it leaves room ([lastLineWidth], the real last-line right edge when
 * the caller can supply it; otherwise the widest line); else it drops to its
 * own line below. Either way it stays right of the text and never overlaps.
 */
@Composable
internal fun BubbleFooterLayout(
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    lastLineWidth: Int? = null,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { content() }
            Box { footer() }
        },
    ) { measurables, constraints ->
        val footerPlaceable = measurables[1].measure(Constraints())
        val contentPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        layoutMeasuredBubbleFooter(
            constraints = constraints,
            content = contentPlaceable,
            footer = footerPlaceable,
            lastLineWidth = lastLineWidth,
            gap = BubbleFooterGap.roundToPx(),
        )
    }
}

private fun MeasureScope.layoutMeasuredBubbleFooter(
    constraints: Constraints,
    content: Placeable,
    footer: Placeable,
    lastLineWidth: Int?,
    gap: Int,
): MeasureResult {
    val effectiveGap = if (footer.width == 0 && footer.height == 0) 0 else gap
    val lastRight = (lastLineWidth ?: content.width).coerceIn(0, content.width)
    val inline = lastRight + effectiveGap + footer.width <= constraints.maxWidth
    if (inline) {
        val width =
            bubbleFooterInlineWidth(
                contentWidth = content.width,
                lastLineRight = lastRight,
                footerWidth = footer.width,
                minWidth = constraints.minWidth,
                maxWidth = constraints.maxWidth,
                gap = effectiveGap,
            )
        return layout(width, content.height) {
            content.place(0, 0)
            footer.place(width - footer.width, content.height - footer.height)
        }
    }
    val width =
        maxOf(content.width, footer.width, constraints.minWidth)
            .coerceAtMost(constraints.maxWidth)
    return layout(width, content.height + footer.height) {
        content.place(0, 0)
        footer.place(width - footer.width, content.height)
    }
}

internal fun bubbleFooterInlineWidth(
    contentWidth: Int,
    lastLineRight: Int,
    footerWidth: Int,
    minWidth: Int,
    maxWidth: Int,
    gap: Int,
): Int =
    maxOf(contentWidth, lastLineRight + gap + footerWidth, minWidth)
        .coerceAtMost(maxWidth)

@Composable
@Suppress("FunctionNaming") // Compose UI entry points use PascalCase.
internal fun BubbleCollapsibleFooterLayout(
    maxBodyHeight: Dp,
    readMore: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    lastLineWidth: Int? = null,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier.clipToBounds(),
        content = {
            Box(
                Modifier.drawWithContent {
                    clipRect(bottom = maxBodyHeight.toPx()) {
                        this@drawWithContent.drawContent()
                    }
                },
            ) { content() }
            readMore()
            Box { footer() }
        },
    ) { measurables, constraints ->
        val maxBodyHeightPx = maxBodyHeight.roundToPx()
        // Let text measure one complete line beyond the visible cap. A one-pixel
        // probe cannot fit line 53, so Text reports only the 52 visible lines and
        // the layout misses the overflow. Production derives maxBodyHeight from
        // this line limit, making the quotient the current scaled line height.
        val overflowProbePx =
            ((maxBodyHeightPx + MESSAGE_COLLAPSE_LINE_LIMIT - 1) / MESSAGE_COLLAPSE_LINE_LIMIT)
                .coerceAtLeast(1)
        val probeHeight =
            (maxBodyHeightPx + overflowProbePx).coerceAtMost(constraints.maxHeight)
        val contentPlaceable =
            measurables[0].measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxHeight = probeHeight,
                ),
            )
        val footerPlaceable =
            measurables[2].measure(Constraints())
        val gap = BubbleFooterGap.roundToPx()
        if (contentPlaceable.height <= maxBodyHeightPx) {
            layoutMeasuredBubbleFooter(
                constraints = constraints,
                content = contentPlaceable,
                footer = footerPlaceable,
                lastLineWidth = lastLineWidth,
                gap = gap,
            )
        } else {
            val readMorePlaceable =
                measurables[1].measure(Constraints())
            layoutCollapsedBubbleFooter(
                constraints = constraints,
                content = contentPlaceable,
                readMore = readMorePlaceable,
                footer = footerPlaceable,
                visibleContentHeight = maxBodyHeightPx,
                gap = gap,
            )
        }
    }
}

private fun MeasureScope.layoutCollapsedBubbleFooter(
    constraints: Constraints,
    content: Placeable,
    readMore: Placeable,
    footer: Placeable,
    visibleContentHeight: Int,
    gap: Int,
): MeasureResult {
    val effectiveGap = if (footer.width == 0 && footer.height == 0) 0 else gap
    val width =
        bubbleCollapsedFooterWidth(
            contentWidth = content.width,
            readMoreWidth = readMore.width,
            footerWidth = footer.width,
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            gap = effectiveGap,
        )
    val rowFits =
        collapsedFooterFitsOnOneRow(
            containerWidth = width,
            readMoreWidth = readMore.width,
            footerWidth = footer.width,
            gap = effectiveGap,
        )
    if (rowFits) {
        val rowMetrics =
            collapsedFooterRowMetrics(
                readMoreHeight = readMore.height,
                readMoreBaseline = readMore[FirstBaseline],
                footerHeight = footer.height,
                footerBaseline = footer[FirstBaseline],
            )
        return layout(width, visibleContentHeight + rowMetrics.height) {
            content.placeRelative(0, 0)
            readMore.placeRelative(0, visibleContentHeight + rowMetrics.readMoreY)
            footer.placeRelative(
                width - footer.width,
                visibleContentHeight + rowMetrics.footerY,
            )
        }
    }
    return layout(width, visibleContentHeight + readMore.height + footer.height) {
        content.placeRelative(0, 0)
        readMore.placeRelative(0, visibleContentHeight)
        footer.placeRelative(
            (width - footer.width).coerceAtLeast(0),
            visibleContentHeight + readMore.height,
        )
    }
}

internal fun bubbleCollapsedFooterWidth(
    contentWidth: Int,
    readMoreWidth: Int,
    footerWidth: Int,
    minWidth: Int,
    maxWidth: Int,
    gap: Int,
): Int =
    maxOf(contentWidth, readMoreWidth + gap + footerWidth, minWidth)
        .coerceAtMost(maxWidth)

internal fun collapsedFooterFitsOnOneRow(
    containerWidth: Int,
    readMoreWidth: Int,
    footerWidth: Int,
    gap: Int,
): Boolean = readMoreWidth + gap + footerWidth <= containerWidth

internal data class CollapsedFooterRowMetrics(
    val height: Int,
    val readMoreY: Int,
    val footerY: Int,
)

internal fun collapsedFooterRowMetrics(
    readMoreHeight: Int,
    readMoreBaseline: Int,
    footerHeight: Int,
    footerBaseline: Int,
): CollapsedFooterRowMetrics {
    val hasBaselines =
        readMoreBaseline != AlignmentLine.Unspecified && footerBaseline != AlignmentLine.Unspecified
    if (!hasBaselines) {
        val height = maxOf(readMoreHeight, footerHeight)
        return CollapsedFooterRowMetrics(
            height = height,
            readMoreY = (height - readMoreHeight) / 2,
            footerY = (height - footerHeight) / 2,
        )
    }

    val aboveBaseline = maxOf(readMoreBaseline, footerBaseline)
    val belowBaseline = maxOf(readMoreHeight - readMoreBaseline, footerHeight - footerBaseline)
    return CollapsedFooterRowMetrics(
        height = aboveBaseline + belowBaseline,
        readMoreY = aboveBaseline - readMoreBaseline,
        footerY = aboveBaseline - footerBaseline,
    )
}
