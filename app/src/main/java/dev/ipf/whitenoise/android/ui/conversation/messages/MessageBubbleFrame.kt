package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
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
    shape: Shape = RoundedCornerShape(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val highlightColor =
        messageTargetHighlightColor(
            customBorderArgb = presentation.borderOverrideArgb,
            fallback = MaterialTheme.colorScheme.tertiary,
        )
    val highlightModifier =
        messageTargetHighlightModifier(
            highlighted = highlighted,
            customBorderArgb = presentation.borderOverrideArgb,
            color = highlightColor,
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
        shape = shape,
        border =
            messageBubbleBorder(
                highlighted = highlighted,
                mine = mine,
                customArgb = presentation.borderOverrideArgb,
                persistedFailure = presentation.suppressBorder,
            ),
        tonalElevation = if (mine) 1.dp else 0.dp,
    ) {
        Column(
            modifier = bubbleContentModifier(mentionModifier, contentModifier),
            verticalArrangement = bubbleContentArrangement,
            content = content,
        )
    }
}

/**
 * One message surface for media and its caption/footer.
 *
 * The media is measured first so the caption adopts its width, but both are
 * clipped and bordered by this single outer surface. Attached media uses
 * square internal corners; the outer surface owns all four visible corners.
 */
@Composable
@Suppress("FunctionNaming")
internal fun MediaCaptionFrame(
    presentation: BubblePresentation,
    highlighted: Boolean,
    mine: Boolean,
    mentionedSelf: Boolean,
    mentionedYouLabel: String,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    media: @Composable ColumnScope.() -> Unit,
    caption: @Composable ColumnScope.() -> Unit,
) {
    val highlightColor =
        messageTargetHighlightColor(
            customBorderArgb = presentation.borderOverrideArgb,
            fallback = MaterialTheme.colorScheme.tertiary,
        )
    val highlightModifier =
        messageTargetHighlightModifier(
            highlighted = highlighted,
            customBorderArgb = presentation.borderOverrideArgb,
            color = highlightColor,
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
        shape = shape,
        border =
            messageBubbleBorder(
                highlighted = highlighted,
                mine = mine,
                customArgb = presentation.borderOverrideArgb,
                persistedFailure = presentation.suppressBorder,
            ),
        tonalElevation = if (mine) 1.dp else 0.dp,
    ) {
        MediaSupplementEnvelope(
            alignEnd = alignEnd,
            media = media,
        ) {
            Column(modifier = contentModifier.fillMaxWidth()) {
                Column(
                    modifier = bubbleContentModifier(mentionModifier, Modifier),
                    verticalArrangement = bubbleContentArrangement,
                    content = caption,
                )
            }
        }
    }
}

/**
 * Measures media first, then gives its supplement exactly the same width.
 *
 * Media children intentionally retain their existing sizing policy: a
 * landscape image, grid, or voice note may consume the available width while
 * a portrait image can keep its fixed card width. Measuring the caption from
 * intrinsic widths would collapse fill-width media to its loading indicator,
 * so the real media measurement is the source of truth instead.
 */
@Composable
@Suppress("FunctionNaming")
internal fun MediaSupplementEnvelope(
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
    media: @Composable ColumnScope.() -> Unit,
    supplement: @Composable ColumnScope.() -> Unit,
) {
    val mediaItemGap = 6.dp
    SubcomposeLayout(modifier) { constraints ->
        val relaxedConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val mediaPlaceable =
            subcompose(MediaEnvelopeSlot.Media) {
                Column(
                    horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(mediaItemGap),
                    content = media,
                )
            }.single().measure(relaxedConstraints)
        val envelopeWidth = constraints.constrainWidth(mediaPlaceable.width)

        val supplementPlaceable =
            subcompose(MediaEnvelopeSlot.Supplement) {
                Column(content = supplement)
            }.single().measure(
                relaxedConstraints.copy(
                    minWidth = envelopeWidth,
                    maxWidth = envelopeWidth,
                ),
            )

        val measuredHeight = mediaPlaceable.height + supplementPlaceable.height
        layout(envelopeWidth, constraints.constrainHeight(measuredHeight)) {
            val mediaX = if (alignEnd) envelopeWidth - mediaPlaceable.width else 0
            mediaPlaceable.placeRelative(mediaX, 0)
            supplementPlaceable.placeRelative(0, mediaPlaceable.height)
        }
    }
}

private enum class MediaEnvelopeSlot {
    Media,
    Supplement,
}

private val bubbleContentArrangement = Arrangement.spacedBy(6.dp)

private fun bubbleContentModifier(
    mentionModifier: Modifier,
    contentModifier: Modifier,
): Modifier =
    mentionModifier
        .then(contentModifier)
        .padding(horizontal = 14.dp, vertical = 10.dp)

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

internal fun messageTargetHighlightColor(
    customBorderArgb: Long?,
    fallback: Color,
): Color = customBorderArgb?.let(::colorFromArgb) ?: fallback

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
