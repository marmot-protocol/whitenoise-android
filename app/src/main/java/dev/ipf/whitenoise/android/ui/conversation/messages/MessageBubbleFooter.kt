package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha

// Gap between a bubble's text and its trailing inline footer.
private val BubbleFooterGap = 8.dp

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
    showRetention: Boolean = false,
) {
    MediaScrimFooter(
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
    ) {
        MessageInlineFooter(
            timeText = timeText,
            color = Color.White,
            showStatus = showStatus,
            status = status,
            editedLabel = null,
            onEditedClick = null,
            showRetention = showRetention,
        )
    }
}

/**
 * Bottom-end footer for a message bubble: an optional "edited" affordance, the
 * disappearing-message indicator, the time, and (outgoing only) the send-status
 * icon, in a subtle tint.
 */
@Composable
internal fun MessageInlineFooter(
    timeText: String,
    color: Color,
    showStatus: Boolean,
    status: MessageStatus,
    editedLabel: String?,
    onEditedClick: (() -> Unit)?,
    showRetention: Boolean = false,
) {
    val spacing = 3.dp
    val timeIndex = (if (editedLabel != null) 1 else 0) + (if (showRetention) 1 else 0)
    Layout(
        content = {
            if (editedLabel != null) {
                Text(
                    text = editedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = if (onEditedClick != null) Modifier.clickable(onClick = onEditedClick) else Modifier,
                )
            }
            if (showRetention) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = stringResource(R.string.disappearing_message),
                    modifier = Modifier.size(12.dp),
                    tint = color.copy(alpha = 0.76f),
                )
            }
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            if (showStatus) {
                OutgoingMessageStatusIcon(status, tint = color)
            }
        },
    ) { measurables, constraints ->
        val relaxedConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(relaxedConstraints) }
        val gap = spacing.roundToPx()
        val contentWidth =
            placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)
        val width = contentWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val contentHeight = placeables.maxOfOrNull { it.height } ?: 0
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        val timePlaceable = placeables[timeIndex]
        val timeY = (height - timePlaceable.height) / 2
        val timeBaseline = timePlaceable[FirstBaseline]
        val alignmentLines =
            if (timeBaseline == AlignmentLine.Unspecified) {
                emptyMap()
            } else {
                mapOf<AlignmentLine, Int>(FirstBaseline to timeY + timeBaseline)
            }

        layout(width, height, alignmentLines) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}

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
    val lastRight = (lastLineWidth ?: content.width).coerceIn(0, content.width)
    val inline = lastRight + gap + footer.width <= constraints.maxWidth
    if (inline) {
        val width =
            bubbleFooterInlineWidth(
                contentWidth = content.width,
                lastLineRight = lastRight,
                footerWidth = footer.width,
                minWidth = constraints.minWidth,
                maxWidth = constraints.maxWidth,
                gap = gap,
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
            footer()
        },
    ) { measurables, constraints ->
        val maxBodyHeightPx = maxBodyHeight.roundToPx()
        val probeHeight = (maxBodyHeightPx + 1).coerceAtMost(constraints.maxHeight)
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
    val width =
        bubbleCollapsedFooterWidth(
            contentWidth = content.width,
            readMoreWidth = readMore.width,
            footerWidth = footer.width,
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            gap = gap,
        )
    val rowFits =
        collapsedFooterFitsOnOneRow(
            containerWidth = width,
            readMoreWidth = readMore.width,
            footerWidth = footer.width,
            gap = gap,
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
