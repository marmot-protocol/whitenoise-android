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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
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
        )
    }
}

/**
 * Bottom-end footer for a message bubble: an optional "edited" affordance, the
 * time, and (outgoing only) the send-status icon, in a subtle tint.
 */
@Composable
internal fun MessageInlineFooter(
    timeText: String,
    color: Color,
    showStatus: Boolean,
    status: MessageStatus,
    editedLabel: String?,
    onEditedClick: (() -> Unit)?,
) {
    val spacing = 3.dp
    val timeIndex = if (editedLabel != null) 1 else 0
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
        val gap = BubbleFooterGap.roundToPx()
        val lastRight = (lastLineWidth ?: contentPlaceable.width).coerceIn(0, contentPlaceable.width)
        val inline = lastRight + gap + footerPlaceable.width <= constraints.maxWidth
        if (inline) {
            val width =
                bubbleFooterInlineWidth(
                    contentWidth = contentPlaceable.width,
                    lastLineRight = lastRight,
                    footerWidth = footerPlaceable.width,
                    minWidth = constraints.minWidth,
                    maxWidth = constraints.maxWidth,
                    gap = gap,
                )
            layout(width, contentPlaceable.height) {
                contentPlaceable.place(0, 0)
                footerPlaceable.place(width - footerPlaceable.width, contentPlaceable.height - footerPlaceable.height)
            }
        } else {
            val width =
                maxOf(contentPlaceable.width, footerPlaceable.width, constraints.minWidth)
                    .coerceAtMost(constraints.maxWidth)
            layout(width, contentPlaceable.height + footerPlaceable.height) {
                contentPlaceable.place(0, 0)
                footerPlaceable.place(width - footerPlaceable.width, contentPlaceable.height)
            }
        }
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

/**
 * Lays collapsed body text above a bottom row with Read More pinned start and
 * the regular footer pinned end. The row stays as narrow as its content unless
 * the body is wider, preserving wrap-content bubbles while keeping the two
 * affordances on the same baseline.
 */
@Composable
internal fun BubbleCollapsedFooterLayout(
    readMore: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { content() }
            readMore()
            footer()
        },
    ) { measurables, constraints ->
        val contentPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        val readMorePlaceable = measurables[1].measure(Constraints())
        val footerPlaceable = measurables[2].measure(Constraints())
        val gap = BubbleFooterGap.roundToPx()
        val width =
            bubbleCollapsedFooterWidth(
                contentWidth = contentPlaceable.width,
                readMoreWidth = readMorePlaceable.width,
                footerWidth = footerPlaceable.width,
                minWidth = constraints.minWidth,
                maxWidth = constraints.maxWidth,
                gap = gap,
            )
        val rowFits =
            collapsedFooterFitsOnOneRow(
                containerWidth = width,
                readMoreWidth = readMorePlaceable.width,
                footerWidth = footerPlaceable.width,
                gap = gap,
            )
        if (rowFits) {
            val readMoreBaseline = readMorePlaceable[FirstBaseline]
            val footerBaseline = footerPlaceable[FirstBaseline]
            val rowMetrics =
                collapsedFooterRowMetrics(
                    readMoreHeight = readMorePlaceable.height,
                    readMoreBaseline = readMoreBaseline,
                    footerHeight = footerPlaceable.height,
                    footerBaseline = footerBaseline,
                )

            layout(width, contentPlaceable.height + rowMetrics.height) {
                contentPlaceable.placeRelative(0, 0)
                readMorePlaceable.placeRelative(0, contentPlaceable.height + rowMetrics.readMoreY)
                footerPlaceable.placeRelative(width - footerPlaceable.width, contentPlaceable.height + rowMetrics.footerY)
            }
        } else {
            layout(width, contentPlaceable.height + readMorePlaceable.height + footerPlaceable.height) {
                contentPlaceable.placeRelative(0, 0)
                readMorePlaceable.placeRelative(0, contentPlaceable.height)
                footerPlaceable.placeRelative(
                    (width - footerPlaceable.width).coerceAtLeast(0),
                    contentPlaceable.height + readMorePlaceable.height,
                )
            }
        }
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
