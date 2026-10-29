package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

// Gap between a bubble's text and its trailing inline footer.
private val BubbleFooterGap = 8.dp

/** Occupied extent from the body's logical start; physical right edges are unsafe in RTL. */
internal fun TextLayoutResult.bubbleFooterLastLineWidth(layoutDirection: LayoutDirection): Int? {
    if (lineCount == 0) return null
    val lastLine = lineCount - 1
    val occupied =
        if (layoutDirection == LayoutDirection.Rtl) size.width - getLineLeft(lastLine) else getLineRight(lastLine)
    return ceil(occupied).toInt().coerceIn(0, size.width)
}

/**
 * Lays [content] with [footer] pinned bottom-end. The footer joins the last
 * line when it leaves room ([lastLineWidth], the occupied extent from logical start when
 * the caller can supply it; otherwise the widest line) and sits on that line's
 * [lastLineBaseline]; else it drops to its own line below. Either way it stays
 * on the text's logical trailing side and never overlaps.
 */
@Composable
internal fun BubbleFooterLayout(
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    lastLineWidth: Int? = null,
    lastLineBaseline: Int? = null,
    allowInline: Boolean = true,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { content() }
            Box { footer() }
        },
    ) { measurables, constraints ->
        val footerPlaceable = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val contentPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        layoutMeasuredBubbleFooter(
            constraints = constraints,
            content = contentPlaceable,
            footer = footerPlaceable,
            lastLineWidth = lastLineWidth,
            lastLineBaseline = lastLineBaseline,
            gap = BubbleFooterGap.roundToPx(),
            allowInline = allowInline,
        )
    }
}

/** Where an inline footer lands beside a bubble's last text line. */
internal data class BubbleInlineFooterGeometry(
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
)

/**
 * The prototype's inline-footer geometry. The footer sits on the last line's baseline rather than on
 * the text block's bottom edge: the footer's box is taller than its text (it carries the 14dp delivery
 * glyph), so bottom-aligning it dropped the timestamp and its glyph below the line they annotate.
 * It only joins the line when it fits and when the line's baseline leaves room for the footer's own.
 */
@Suppress("LongParameterList") // One geometry decision; splitting it would hide the relationships.
internal fun bubbleInlineFooterGeometry(
    textWidth: Int,
    textHeight: Int,
    lastLineRight: Int,
    lastBaseline: Int,
    footerWidth: Int,
    footerHeight: Int,
    footerBaseline: Int,
    maxWidth: Int,
    minWidth: Int,
    gap: Int,
    allowInline: Boolean = true,
): BubbleInlineFooterGeometry {
    val required = lastLineRight + gap + footerWidth
    val inline = allowInline && required <= maxWidth && lastBaseline >= footerBaseline
    val width = maxOf(textWidth, footerWidth, minWidth, if (inline) required else 0).coerceAtMost(maxWidth)
    val y = if (inline) lastBaseline - footerBaseline else textHeight + gap / 2
    return BubbleInlineFooterGeometry(width, maxOf(textHeight, y + footerHeight), width - footerWidth, y)
}

private fun MeasureScope.layoutMeasuredBubbleFooter(
    constraints: Constraints,
    content: Placeable,
    footer: Placeable,
    lastLineWidth: Int?,
    lastLineBaseline: Int?,
    gap: Int,
    allowInline: Boolean,
): MeasureResult {
    val effectiveGap = if (footer.width == 0 && footer.height == 0) 0 else gap
    val lastRight = (lastLineWidth ?: content.width).coerceIn(0, content.width)
    val firstFooterBaseline = footer[FirstBaseline]
    val lastFooterBaseline = footer[LastBaseline]
    val footerBaseline =
        lastFooterBaseline.takeIf { it != AlignmentLine.Unspecified }
            ?: firstFooterBaseline.takeIf { it != AlignmentLine.Unspecified }
            ?: footer.height
    // A reported text baseline is local to the text block that measured it, so a body built from
    // several blocks placed the footer against the wrong one. The body's own last baseline is
    // already expressed in the coordinates the footer is placed in, so prefer it and keep the
    // reported line only for content that publishes no baseline of its own.
    // Without a measured baseline the footer keeps its previous bottom-aligned position, expressed as
    // the baseline that produces it, so a caller that cannot report one is not moved by this rule.
    val lastBaseline =
        content[LastBaseline].takeIf { it != AlignmentLine.Unspecified }
            ?: lastLineBaseline
            ?: (content.height - footer.height + footerBaseline)
    val geometry =
        bubbleInlineFooterGeometry(
            textWidth = content.width,
            textHeight = content.height,
            lastLineRight = lastRight,
            lastBaseline = lastBaseline,
            footerWidth = footer.width,
            footerHeight = footer.height,
            footerBaseline = footerBaseline,
            maxWidth = constraints.maxWidth,
            minWidth = constraints.minWidth,
            gap = effectiveGap,
            allowInline = allowInline && footer.hasSingleFooterBaseline(),
        )
    return layout(geometry.width, geometry.height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
        content.placeRelative(0, 0)
        footer.placeRelative(geometry.x, geometry.y)
    }
}

/** A wrapped metadata footer must never rise into earlier lines of a multi-block body. */
private fun Placeable.hasSingleFooterBaseline(): Boolean =
    this[FirstBaseline] == AlignmentLine.Unspecified ||
        this[LastBaseline] == AlignmentLine.Unspecified ||
        this[FirstBaseline] == this[LastBaseline]

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
    lastLineBaseline: Int? = null,
    allowInline: Boolean = true,
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
            measurables[2].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = BubbleFooterGap.roundToPx()
        if (contentPlaceable.height <= maxBodyHeightPx) {
            layoutMeasuredBubbleFooter(
                constraints = constraints,
                content = contentPlaceable,
                footer = footerPlaceable,
                lastLineWidth = lastLineWidth,
                lastLineBaseline = lastLineBaseline,
                gap = gap,
                allowInline = allowInline,
            )
        } else {
            val readMorePlaceable =
                measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            layoutCollapsedBubbleFooter(
                constraints = constraints,
                content = contentPlaceable,
                readMore = readMorePlaceable,
                footer = footerPlaceable,
                visibleContentHeight = maxBodyHeightPx,
                gap = gap,
                allowInline = allowInline,
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
    allowInline: Boolean,
): MeasureResult {
    val effectiveGap = if (footer.width == 0 && footer.height == 0) 0 else gap
    val canShareRow = allowInline && footer.hasSingleFooterBaseline()
    val width =
        bubbleCollapsedFooterWidth(
            contentWidth = content.width,
            readMoreWidth = readMore.width,
            footerWidth = footer.width,
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            gap = effectiveGap,
            allowInline = canShareRow,
        )
    val rowFits =
        canShareRow &&
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
    allowInline: Boolean = true,
): Int =
    maxOf(contentWidth, if (allowInline) readMoreWidth + gap + footerWidth else maxOf(readMoreWidth, footerWidth), minWidth)
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
