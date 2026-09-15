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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
    retention: RetentionIndicatorInput? = null,
    reserveRetentionSpace: Boolean = false,
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
            retention = retention,
            reserveRetentionSpace = reserveRetentionSpace,
            statusContainerColor = Color.Black,
        )
    }
}

/** Bottom-end edited, retention, timestamp, and outgoing-status chrome. */
@Composable
internal fun MessageInlineFooter(
    timeText: String,
    color: Color,
    showStatus: Boolean,
    status: MessageStatus,
    editedLabel: String?,
    onEditedClick: (() -> Unit)?,
    retention: RetentionIndicatorInput? = null,
    retentionClockMillis: () -> Long = System::currentTimeMillis,
    reserveRetentionSpace: Boolean = false,
    showTime: Boolean = true,
    modifier: Modifier = Modifier,
    statusContainerColor: Color? = null,
) {
    val retentionPresentation = rememberRetentionIndicatorPresentation(retention, retentionClockMillis)
    val showRetention =
        reserveRetentionSpace || retentionPresentation !is RetentionIndicatorPresentation.Hidden
    val baselineIndex = footerBaselineIndex(showTime, editedLabel != null, showRetention, showStatus)
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
                retentionPresentation = retentionPresentation,
                reserveRetentionSpace = reserveRetentionSpace,
                showTime = showTime,
                statusContainerColor = statusContainerColor,
            )
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gaps = footerItemGaps(placeables.size, showRetention, FooterItemSpacing, FooterRetentionSpacing)
        val contentWidth = placeables.sumOf { it.width } + gaps.sum()
        val width = contentWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val baselinePlaceable = baselineIndex?.let(placeables::get)
        val baselineY = baselinePlaceable?.let { (height - it.height) / 2 }
        val baseline = baselinePlaceable?.get(FirstBaseline) ?: AlignmentLine.Unspecified
        val alignmentLines =
            if (baseline == AlignmentLine.Unspecified || baselineY == null) {
                emptyMap()
            } else {
                mapOf<AlignmentLine, Int>(FirstBaseline to baselineY + baseline)
            }
        layout(width, height, alignmentLines) {
            var x = 0
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gaps.getOrElse(index) { 0 }
            }
        }
    }
}

/**
 * Footer items in prototype order: the disappearing-message clock leads the
 * row, then the edited label, the outgoing delivery glyph, and the timestamp.
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
    retentionPresentation: RetentionIndicatorPresentation,
    reserveRetentionSpace: Boolean,
    showTime: Boolean,
    statusContainerColor: Color?,
) {
    if (reserveRetentionSpace || retentionPresentation !is RetentionIndicatorPresentation.Hidden) {
        MessageRetentionIndicatorSlot(retentionPresentation, color, reserveRetentionSpace)
    }
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

/** The time carries the baseline; it follows the retention slot, the edited label and the delivery glyph. */
private fun footerBaselineIndex(
    showTime: Boolean,
    hasEditedLabel: Boolean,
    showRetention: Boolean,
    showStatus: Boolean,
): Int? =
    when {
        showTime -> (if (hasEditedLabel) 1 else 0) + (if (showRetention) 1 else 0) + (if (showStatus) 1 else 0)
        hasEditedLabel -> if (showRetention) 1 else 0
        else -> null
    }

/**
 * The gap that follows each footer item. The leading disappearing-message clock
 * keeps the prototype's wider [FooterRetentionSpacing] from the timestamp group;
 * everything else is separated by [FooterItemSpacing].
 */
private fun Density.footerItemGaps(
    itemCount: Int,
    retentionLeads: Boolean,
    itemSpacing: Dp,
    retentionSpacing: Dp,
): List<Int> {
    val defaultGap = itemSpacing.roundToPx()
    val leadingGap = retentionSpacing.roundToPx()
    return List((itemCount - 1).coerceAtLeast(0)) { index ->
        if (retentionLeads && index == 0) leadingGap else defaultGap
    }
}

private val FooterItemSpacing = 3.dp
private val FooterRetentionSpacing = 4.dp
