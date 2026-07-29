package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shared frame for caption and plain-text bubbles. */
@Composable
@Suppress("FunctionNaming")
internal fun MessageBubbleFrame(
    presentation: BubblePresentation,
    highlighted: Boolean,
    mine: Boolean,
    mentionedSelf: Boolean,
    mentionedYouLabel: String,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val highlightModifier =
        messageTargetHighlightModifier(
            highlighted = highlighted,
            customBorderArgb = presentation.borderOverrideArgb,
            color = MaterialTheme.colorScheme.tertiary,
        )
    val mentionModifier =
        messageMentionRailModifier(
            mentionedSelf = mentionedSelf,
            mentionedYouLabel = mentionedYouLabel,
            accentArgb = presentation.mentionAccentArgb,
        )

    Surface(
        modifier = modifier.then(highlightModifier),
        color = colorFromArgb(presentation.backgroundArgb),
        contentColor = colorFromArgb(presentation.contentArgb),
        shape = RoundedCornerShape(18.dp),
        border = messageBubbleBorder(highlighted, mine, presentation.borderOverrideArgb),
        tonalElevation = if (mine) 1.dp else 0.dp,
    ) {
        Column(
            modifier =
                mentionModifier
                    .then(contentModifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun MessageBubbleInvalidationWarning(
    warning: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = warning,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}

internal fun shouldFrameMessageBubbleSupplement(
    bodyText: String?,
    invalidationWarning: String?,
): Boolean = bodyText != null || invalidationWarning != null

private fun messageTargetHighlightModifier(
    highlighted: Boolean,
    customBorderArgb: Long?,
    color: Color,
): Modifier =
    if (highlighted && customBorderArgb != null) {
        Modifier.drawWithContent {
            drawContent()
            val inset = 4.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    } else {
        Modifier
    }

private fun messageMentionRailModifier(
    mentionedSelf: Boolean,
    mentionedYouLabel: String,
    accentArgb: Long,
): Modifier =
    if (mentionedSelf) {
        Modifier
            .semantics { contentDescription = mentionedYouLabel }
            .drawBehind {
                val railWidth = 3.dp.toPx()
                val inset = 4.dp.toPx()
                drawRoundRect(
                    color = colorFromArgb(accentArgb),
                    topLeft = Offset(inset, inset),
                    size = Size(railWidth, (size.height - inset * 2).coerceAtLeast(railWidth)),
                    cornerRadius = CornerRadius(railWidth / 2f, railWidth / 2f),
                )
            }
    } else {
        Modifier
    }
