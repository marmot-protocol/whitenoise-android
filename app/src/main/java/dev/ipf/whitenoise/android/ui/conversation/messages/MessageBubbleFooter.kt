package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.MessageStatus

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
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(percent = 50))
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
