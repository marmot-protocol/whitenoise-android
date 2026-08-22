package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

internal const val MESSAGE_TARGET_HIGHLIGHT_FADE_MILLIS = 300

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
    val highlightProgress =
        animateFloatAsState(
            targetValue = if (highlighted) 1f else 0f,
            animationSpec = tween(durationMillis = MESSAGE_TARGET_HIGHLIGHT_FADE_MILLIS),
            label = "messageTargetHighlight",
        )
    val highlightColor =
        messageTargetHighlightColor(
            customBorderArgb = presentation.borderOverrideArgb,
            fallback = MaterialTheme.colorScheme.tertiary,
        )
    val highlightModifier =
        messageTargetHighlightModifier(
            progress = highlightProgress,
            customBorderArgb = presentation.borderOverrideArgb,
            color = highlightColor,
            enabled = !presentation.suppressBorder,
        )
    val mentionModifier =
        messageMentionFrameModifier(
            mentionedSelf = mentionedSelf,
            mentionedYouLabel = mentionedYouLabel,
            accentArgb = presentation.mentionAccentArgb,
            integratedWithBorder = isAmoledSurfaceTheme(),
        )

    Surface(
        modifier = modifier.then(highlightModifier).then(mentionModifier),
        color = colorFromArgb(presentation.backgroundArgb),
        contentColor = colorFromArgb(presentation.contentArgb),
        shape = shape,
        border =
            messageBubbleBorder(
                highlighted = false,
                mine = mine,
                customArgb = presentation.borderOverrideArgb,
                persistedFailure = presentation.suppressBorder,
            ),
        tonalElevation = if (mine) 1.dp else 0.dp,
    ) {
        Column(
            modifier = bubbleContentModifier(contentModifier),
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
    val highlightProgress =
        animateFloatAsState(
            targetValue = if (highlighted) 1f else 0f,
            animationSpec = tween(durationMillis = MESSAGE_TARGET_HIGHLIGHT_FADE_MILLIS),
            label = "mediaTargetHighlight",
        )
    val highlightColor =
        messageTargetHighlightColor(
            customBorderArgb = presentation.borderOverrideArgb,
            fallback = MaterialTheme.colorScheme.tertiary,
        )
    val highlightModifier =
        messageTargetHighlightModifier(
            progress = highlightProgress,
            customBorderArgb = presentation.borderOverrideArgb,
            color = highlightColor,
            enabled = !presentation.suppressBorder,
        )
    val mentionModifier =
        messageMentionFrameModifier(
            mentionedSelf = mentionedSelf,
            mentionedYouLabel = mentionedYouLabel,
            accentArgb = presentation.mentionAccentArgb,
            integratedWithBorder = isAmoledSurfaceTheme(),
        )

    Surface(
        modifier = modifier.then(highlightModifier).then(mentionModifier),
        color = colorFromArgb(presentation.backgroundArgb),
        contentColor = colorFromArgb(presentation.contentArgb),
        shape = shape,
        border =
            messageBubbleBorder(
                highlighted = false,
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
                    modifier = bubbleContentModifier(Modifier),
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

private fun bubbleContentModifier(contentModifier: Modifier): Modifier =
    contentModifier
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
    progress: State<Float>,
    customBorderArgb: Long?,
    color: Color,
    enabled: Boolean,
): Modifier =
    if (enabled) {
        Modifier.drawWithContent {
            drawContent()
            val alpha = progress.value.coerceIn(0f, 1f)
            if (alpha <= 0f) return@drawWithContent
            val inset = if (customBorderArgb != null) 4.dp.toPx() else 1.dp.toPx()
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(inset, inset),
                size = Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f)),
                cornerRadius =
                    if (customBorderArgb != null) {
                        CornerRadius(14.dp.toPx(), 14.dp.toPx())
                    } else {
                        CornerRadius(17.dp.toPx(), 17.dp.toPx())
                    },
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    } else {
        Modifier
    }

private fun messageMentionFrameModifier(
    mentionedSelf: Boolean,
    mentionedYouLabel: String,
    accentArgb: Long,
    integratedWithBorder: Boolean,
): Modifier =
    if (mentionedSelf) {
        Modifier
            .semantics { contentDescription = mentionedYouLabel }
            .drawWithCache {
                val railWidth = 3.dp.toPx()
                val bounds =
                    messageMentionRailBounds(
                        frameSize = size,
                        layoutDirection = layoutDirection,
                        railWidth = railWidth,
                        edgeInset = if (integratedWithBorder) 1.dp.toPx() else 4.dp.toPx(),
                        verticalInset = if (integratedWithBorder) 14.dp.toPx() else 4.dp.toPx(),
                    )
                val railColor = colorFromArgb(accentArgb)
                onDrawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = railColor,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        cornerRadius = CornerRadius(bounds.width / 2f, bounds.width / 2f),
                    )
                }
            }
    } else {
        Modifier
    }

internal fun messageMentionRailBounds(
    frameSize: Size,
    layoutDirection: LayoutDirection,
    railWidth: Float,
    edgeInset: Float,
    verticalInset: Float,
): Rect {
    val safeRailWidth = railWidth.coerceIn(0f, frameSize.width.coerceAtLeast(0f))
    val horizontalRange = (frameSize.width - safeRailWidth).coerceAtLeast(0f)
    val safeEdgeInset = edgeInset.coerceIn(0f, horizontalRange)
    val maxVerticalInset = ((frameSize.height - safeRailWidth) / 2f).coerceAtLeast(0f)
    val safeVerticalInset = verticalInset.coerceIn(0f, maxVerticalInset)
    val left =
        when (layoutDirection) {
            LayoutDirection.Ltr -> safeEdgeInset
            LayoutDirection.Rtl -> horizontalRange - safeEdgeInset
        }
    return Rect(
        left = left,
        top = safeVerticalInset,
        right = left + safeRailWidth,
        bottom = (frameSize.height - safeVerticalInset).coerceAtLeast(safeVerticalInset),
    )
}
