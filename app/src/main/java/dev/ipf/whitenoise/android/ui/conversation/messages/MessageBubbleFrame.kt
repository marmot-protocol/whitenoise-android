package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.Avatar

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
    showIdentityHeader: Boolean = false,
    identityHeader: @Composable ColumnScope.() -> Unit = {},
    media: @Composable ColumnScope.() -> Unit,
    caption: @Composable ColumnScope.() -> Unit,
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
            header =
                if (showIdentityHeader) {
                    {
                        Column(
                            modifier =
                                Modifier
                                    .padding(horizontal = 14.dp)
                                    .padding(top = 10.dp),
                        ) {
                            identityHeader()
                        }
                    }
                } else {
                    null
                },
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
 * Measures media first, then gives its optional header and supplement exactly
 * the same width. A long sender name therefore ellipsizes instead of widening
 * an otherwise narrow portrait image or file card.
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
    header: (@Composable ColumnScope.() -> Unit)? = null,
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

        val headerPlaceable =
            header?.let { headerContent ->
                subcompose(MediaEnvelopeSlot.Header) {
                    Column(content = headerContent)
                }.single().measure(
                    relaxedConstraints.copy(
                        minWidth = envelopeWidth,
                        maxWidth = envelopeWidth,
                    ),
                )
            }
        val supplementPlaceable =
            subcompose(MediaEnvelopeSlot.Supplement) {
                Column(content = supplement)
            }.single().measure(
                relaxedConstraints.copy(
                    minWidth = envelopeWidth,
                    maxWidth = envelopeWidth,
                ),
            )

        val headerHeight = headerPlaceable?.height ?: 0
        val measuredHeight = headerHeight + mediaPlaceable.height + supplementPlaceable.height
        layout(envelopeWidth, constraints.constrainHeight(measuredHeight)) {
            val mediaX = if (alignEnd) envelopeWidth - mediaPlaceable.width else 0
            headerPlaceable?.placeRelative(0, 0)
            mediaPlaceable.placeRelative(mediaX, headerHeight)
            supplementPlaceable.placeRelative(0, headerHeight + mediaPlaceable.height)
        }
    }
}

private enum class MediaEnvelopeSlot {
    Header,
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

@Composable
@Suppress("FunctionNaming")
internal fun MessageBubbleSenderHeader(
    name: String,
    seed: String,
    avatarUrl: String?,
    profileLabel: String,
    contentColor: Color,
    onProfileClick: () -> Unit,
    onLongPress: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .widthIn(min = 48.dp)
                .heightIn(min = 48.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClickLabel = profileLabel,
                    role = Role.Button,
                    onClick = onProfileClick,
                    onLongClick = onLongPress,
                ).semantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            title = name,
            seed = seed,
            size = 20.dp,
            pictureUrl = avatarUrl,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun shouldFrameMessageBubbleSupplement(
    bodyText: String?,
    invalidationWarning: String?,
    showSenderHeader: Boolean = false,
): Boolean = bodyText != null || invalidationWarning != null || showSenderHeader

internal fun messageBubbleSupplementContentColor(
    supplementInsideBubble: Boolean,
    bubbleContentColor: Color,
    outsideContentColor: Color,
): Color = if (supplementInsideBubble) bubbleContentColor else outsideContentColor

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
