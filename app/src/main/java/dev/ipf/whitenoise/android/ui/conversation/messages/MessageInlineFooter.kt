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
    showTime: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val retentionPresentation = rememberRetentionIndicatorPresentation(retention, retentionClockMillis)
    val showRetention = retentionPresentation !is RetentionIndicatorPresentation.Hidden
    val baselineIndex = footerBaselineIndex(showTime, editedLabel != null, showRetention)
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
                showTime = showTime,
            )
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gap = FooterItemSpacing.roundToPx()
        val contentWidth = placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)
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
            placeables.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}

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
    showTime: Boolean,
) {
    editedLabel?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = if (onEditedClick != null) Modifier.clickable(onClick = onEditedClick) else Modifier,
        )
    }
    if (retentionPresentation !is RetentionIndicatorPresentation.Hidden) {
        MessageRetentionIndicator(retentionPresentation, color)
    }
    if (showTime) {
        Text(timeText, style = MaterialTheme.typography.labelSmall, color = color)
    }
    if (showStatus) {
        OutgoingMessageStatusIcon(status, tint = color)
    }
}

private fun footerBaselineIndex(
    showTime: Boolean,
    hasEditedLabel: Boolean,
    showRetention: Boolean,
): Int? =
    when {
        showTime -> (if (hasEditedLabel) 1 else 0) + (if (showRetention) 1 else 0)
        hasEditedLabel -> 0
        else -> null
    }

private val FooterItemSpacing = 3.dp
