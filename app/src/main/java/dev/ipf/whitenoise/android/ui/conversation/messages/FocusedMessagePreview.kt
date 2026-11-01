@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.MarkdownTimestampText
import dev.ipf.whitenoise.android.ui.markdownDocumentProfileReferenceBech32s
import dev.ipf.whitenoise.android.ui.markdownDocumentTimestamps
import dev.ipf.whitenoise.android.ui.markdownDocumentToPreviewAnnotatedString
import dev.ipf.whitenoise.android.ui.markdownInlinesToAnnotatedString
import dev.ipf.whitenoise.android.ui.previewTake
import dev.ipf.whitenoise.android.ui.rememberTimestampRevision

/** Display-only excerpt keeps native inline styling but removes all actionable link annotations. */
internal fun focusedMessagePreviewText(
    source: String,
    document: MarkdownDocumentFfi?,
    mentionDisplayName: ((String) -> String?)? = null,
): AnnotatedString {
    if (document == null || document.blocks.isEmpty()) return EmojiShortcodes.annotate(AnnotatedString(source))
    val paragraph = document.blocks.singleOrNull() as? MarkdownBlockFfi.Paragraph
    val preview =
        if (paragraph != null) {
            val styled =
                markdownInlinesToAnnotatedString(
                    inlines = paragraph.inlines,
                    codeStyle = SpanStyle(fontFamily = FontFamily.Monospace),
                    linkStyle = SpanStyle(textDecoration = TextDecoration.Underline),
                    mentionDisplayName = mentionDisplayName,
                    useDecorativeBackgrounds = false,
                )
            val inertLinkStyles =
                styled.getLinkAnnotations(0, styled.length).mapNotNull { range ->
                    range.item.styles
                        ?.style
                        ?.let { style -> AnnotatedString.Range(style, range.start, range.end) }
                }
            EmojiShortcodes.annotate(
                buildAnnotatedString {
                    append(styled.text)
                    (styled.spanStyles + inertLinkStyles).forEach { addStyle(it.item, it.start, it.end) }
                    styled
                        .getStringAnnotations(
                            0,
                            styled.length,
                        ).forEach { addStringAnnotation(it.tag, it.item, it.start, it.end) }
                },
            )
        } else {
            focusedMultiBlockPreview(document, mentionDisplayName)
        }
    return preview.withFocusedProfileDirection(document)
}

/** Keeps authored block separators outside any fragment paragraph style. */
private fun focusedMultiBlockPreview(
    document: MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)?,
): AnnotatedString =
    buildAnnotatedString {
        for (block in document.blocks) {
            if (length >= FOCUSED_PREVIEW_TEXT_BUDGET) break
            if (length > 0) append("\n\n")
            val remaining = (FOCUSED_PREVIEW_TEXT_BUDGET - length).coerceAtLeast(0)
            append(
                when (block) {
                    is MarkdownBlockFfi.CodeBlock -> AnnotatedString(block.content.previewTake(remaining))
                    is MarkdownBlockFfi.MathBlock -> AnnotatedString(block.content.previewTake(remaining))
                    else ->
                        markdownDocumentToPreviewAnnotatedString(
                            document = document.copy(blocks = listOf(block)),
                            codeStyle = SpanStyle(fontFamily = FontFamily.Monospace),
                            maxLength = remaining,
                            mentionDisplayName = mentionDisplayName,
                            applyProfileDirection = false,
                        )
                },
            )
        }
    }

/** Applies profile direction once after both single- and multi-block excerpts are assembled. */
private fun AnnotatedString.withFocusedProfileDirection(document: MarkdownDocumentFfi): AnnotatedString =
    if (isNotEmpty() && markdownDocumentProfileReferenceBech32s(document).isNotEmpty()) {
        buildAnnotatedString {
            append(this@withFocusedProfileDirection)
            addStyle(ParagraphStyle(textDirection = TextDirection.Content), 0, length)
        }
    } else {
        this
    }

/** Five-line text presentation uses the native frame, typography and delivery footer without a controller. */
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun FocusedTextMessagePreview(
    presentation: BubblePresentation,
    mine: Boolean,
    text: String,
    document: MarkdownDocumentFfi?,
    time: String,
    status: MessageStatus,
    showStatus: Boolean,
    senderName: String? = null,
    mentionDisplayName: ((String) -> String?)? = null,
    reply: (@Composable () -> Unit)? = null,
    media: (@Composable () -> Unit)? = null,
    warning: String? = null,
    editedLabel: String? = null,
    mentionedSelf: Boolean = false,
    mentionedYouLabel: String = "",
    footerContent: (@Composable () -> Unit)? = null,
) {
    val timestampRevision =
        rememberTimestampRevision(remember(document) { document?.let(::markdownDocumentTimestamps).orEmpty() })
    val excerpt =
        remember(text, document, mentionDisplayName, timestampRevision) {
            focusedMessagePreviewText(text, document, mentionDisplayName)
        }
    var lastLineLayout by remember(excerpt) { mutableStateOf<TextLayoutResult?>(null) }
    val layoutDirection = LocalLayoutDirection.current
    val lastLineWidth = lastLineLayout?.bubbleFooterLastLineWidth(layoutDirection)
    val lastLineBaseline =
        lastLineLayout?.let { layout ->
            if (layout.lineCount > 0) layout.getLineBaseline(layout.lineCount - 1).toInt() else null
        }
    val footer: @Composable () -> Unit =
        footerContent ?: {
            MessageInlineFooter(
                timeText = time,
                color = colorFromArgb(presentation.contentArgb),
                showStatus = showStatus,
                status = status,
                editedLabel = editedLabel,
                onEditedClick = null,
                statusContainerColor = colorFromArgb(presentation.backgroundArgb),
            )
        }
    val excerptStyle = MaterialTheme.typography.bodyLarge
    val sender: (@Composable () -> Unit)? =
        senderName?.let { name -> { EmojiLabel(name, style = MaterialTheme.typography.labelMedium) } }
    val main: @Composable (Boolean) -> Unit = { compact ->
        BubbleFooterLayout(
            footer = footer,
            lastLineWidth = { lastLineWidth.takeIf { warning == null } },
            lastLineBaseline = { lastLineBaseline.takeIf { warning == null } },
        ) {
            ExcerptAboveWarning(
                excerptLineHeight = excerptStyle.lineHeight.takeIf { it.isSpecified } ?: (excerptStyle.fontSize * 1.5f),
                excerpt = {
                    MarkdownTimestampText(
                        text = excerpt,
                        style = excerptStyle,
                        maxLines = if (compact) 1 else FOCUSED_PREVIEW_TEXT_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("message-actions-excerpt"),
                        onTextLayout = { layout ->
                            lastLineLayout = layout
                        },
                    )
                },
                warning = {
                    warning?.let {
                        MessageBubbleInvalidationWarning(it, color = colorFromArgb(presentation.contentArgb))
                    }
                },
            )
        }
    }
    if (media == null) {
        MessageBubbleFrame(
            presentation = presentation,
            highlighted = false,
            mine = mine,
            mentionedSelf = mentionedSelf,
            mentionedYouLabel = mentionedYouLabel,
        ) {
            FocusedTextBudgetLayout(quote = reply, sender = sender) { main(false) }
        }
    } else {
        BoxWithConstraints {
            val compact = maxHeight <= 200.dp
            // The media envelope measures artwork before its caption. In a short IME frame a
            // full-height artwork would otherwise consume the entire preview and clip the
            // caption/footer. Retain an unscaled crop and reserve room for native-size text.
            val mediaHeightLimit = (maxHeight - 100.dp).coerceAtLeast(24.dp)
            Column(
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!compact) reply?.invoke()
                MediaCaptionFrame(
                    presentation = presentation,
                    highlighted = false,
                    mine = mine,
                    mentionedSelf = mentionedSelf,
                    mentionedYouLabel = mentionedYouLabel,
                    alignEnd = mine,
                    media = {
                        if (compact) {
                            Box(
                                Modifier
                                    .heightIn(max = mediaHeightLimit)
                                    .clipToBounds()
                                    .testTag("message-actions-media-viewport"),
                            ) { media() }
                        } else {
                            media()
                        }
                    },
                ) {
                    sender?.invoke()
                    main(compact)
                }
            }
        }
    }
}

/**
 * Stacks the excerpt above its warning, measuring the warning first: a bounded frame shortens the excerpt
 * rather than squeezing the warning out of view, and without a bound both keep their natural height. Its
 * minimum intrinsic height is the warning plus one excerpt line of [excerptLineHeight].
 */
@Composable
private fun ExcerptAboveWarning(
    excerptLineHeight: TextUnit,
    excerpt: @Composable () -> Unit,
    warning: @Composable () -> Unit,
) {
    val policy = remember(excerptLineHeight) { ExcerptAboveWarningPolicy(excerptLineHeight) }
    Layout(contents = listOf(excerpt, warning), measurePolicy = policy)
}

/** Measure policy for [ExcerptAboveWarning]. */
private class ExcerptAboveWarningPolicy(
    private val excerptLineHeight: TextUnit,
) : MultiContentMeasurePolicy {
    /** Measures the warning first and gives the excerpt the height left, or no bound when there is none. */
    override fun MeasureScope.measure(
        measurables: List<List<Measurable>>,
        constraints: Constraints,
    ): MeasureResult {
        val (excerptMeasurables, warningMeasurables) = measurables
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val warnings = warningMeasurables.map { it.measure(loose) }
        val warningHeight = warnings.sumOf { it.height }
        val excerptMaxHeight =
            if (constraints.hasBoundedHeight) {
                (constraints.maxHeight - warningHeight).coerceAtLeast(0)
            } else {
                Constraints.Infinity
            }
        val excerpts = excerptMeasurables.map { it.measure(loose.copy(maxHeight = excerptMaxHeight)) }
        val excerptHeight = excerpts.maxOfOrNull { it.height } ?: 0
        val width = (excerpts + warnings).maxOfOrNull { it.width } ?: 0
        return layout(
            width.coerceIn(constraints.minWidth, constraints.maxWidth),
            (excerptHeight + warningHeight).coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            excerpts.forEach { it.placeRelative(0, 0) }
            var y = excerptHeight
            warnings.forEach {
                it.placeRelative(0, y)
                y += it.height
            }
        }
    }

    /** The least height that still shows one whole excerpt line above the full warning. */
    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<List<IntrinsicMeasurable>>,
        width: Int,
    ): Int {
        val (excerptMeasurables, warningMeasurables) = measurables
        val line = if (excerptMeasurables.isEmpty()) 0 else excerptLineHeight.roundToPx()
        return line + warningMeasurables.sumOf { it.minIntrinsicHeight(width) }
    }
}

/**
 * Fits a focused text preview to the height it is given from measured content, not a fixed threshold
 * (issue #3532). The minimum is the [main] body's minimum intrinsic height: one excerpt line, any warning
 * and the timestamp/status footer. The [sender] label is kept only while the minimum still fits with it,
 * and the [quote] only while it fits whole above that. The main body then takes the remaining height,
 * where the excerpt shortens to whole lines. Without a height bound everything is shown. Each part is
 * composed once, so nothing is measured twice or duplicated in semantics.
 */
@Composable
private fun FocusedTextBudgetLayout(
    quote: (@Composable () -> Unit)?,
    sender: (@Composable () -> Unit)?,
    main: @Composable () -> Unit,
) {
    Layout(contents = listOf(quote ?: {}, sender ?: {}, main), measurePolicy = FocusedTextBudgetPolicy)
}

/** Measure policy for [FocusedTextBudgetLayout]. */
private object FocusedTextBudgetPolicy : MultiContentMeasurePolicy {
    /** Keeps the sender, then the quote, only while they fit above the main body's minimum. */
    override fun MeasureScope.measure(
        measurables: List<List<Measurable>>,
        constraints: Constraints,
    ): MeasureResult {
        val (quoteMeasurables, senderMeasurables, mainMeasurables) = measurables
        val gap = BubbleContentGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bounded = constraints.hasBoundedHeight
        val limit = if (bounded) constraints.maxHeight else Constraints.Infinity
        val mainMinimum = if (bounded) mainMeasurables.sumOf { it.minIntrinsicHeight(loose.maxWidth) } else 0
        val sender = measureBlock(senderMeasurables, loose, gap).ifFits(above = mainMinimum, limit = limit)
        val belowQuote = mainMinimum + sender.height
        val quote = measureBlock(quoteMeasurables, loose, gap).ifFits(above = belowQuote, limit = limit)
        val top = quote.height + sender.height
        val mainConstraints = if (bounded) loose.copy(maxHeight = (limit - top).coerceAtLeast(0)) else loose
        val mains = mainMeasurables.map { it.measure(mainConstraints) }
        val width = (mains + quote.placeables + sender.placeables).maxOfOrNull { it.width } ?: 0
        val height = top + (mains.maxOfOrNull { it.height } ?: 0)
        return layout(
            width.coerceIn(constraints.minWidth, constraints.maxWidth),
            height.coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            quote.placeables.forEach { it.placeRelative(0, 0) }
            sender.placeables.forEach { it.placeRelative(0, quote.height) }
            mains.forEach { it.placeRelative(0, top) }
        }
    }

    /** Measures an optional block at its natural height; its height includes the gap below it. */
    private fun measureBlock(
        measurables: List<Measurable>,
        loose: Constraints,
        gap: Int,
    ): Block {
        if (measurables.isEmpty()) return Block.Empty
        val placeables = measurables.map { it.measure(loose.copy(maxHeight = Constraints.Infinity)) }
        return Block(placeables, placeables.maxOf { it.height } + gap)
    }
}

/** An optional block of a focused preview, shown whole or not at all. */
private class Block(
    val placeables: List<Placeable>,
    val height: Int,
) {
    /** This block when it fits whole above [above] within [limit], else an empty block. */
    fun ifFits(
        above: Int,
        limit: Int,
    ): Block = if (placeables.isEmpty() || height + above <= limit) this else Empty

    companion object {
        val Empty = Block(emptyList(), 0)
    }
}

/** Replays a natively remeasured display layer at one pixel per pixel; metadata is never GPU-scaled. */
@Composable
internal fun FocusedRenderedMessagePreview(
    layer: GraphicsLayer,
    sourceSize: IntSize,
) {
    if (sourceSize.width <= 0 || sourceSize.height <= 0) return
    val density = LocalDensity.current
    Box(
        Modifier
            .size(
                width = with(density) { sourceSize.width.toDp() },
                height = with(density) { sourceSize.height.toDp() },
            ).clipToBounds()
            .drawWithContent { drawLayer(layer) },
    )
}

/** Inert preview semantics still expose the actual author, content/media identity and time. */
internal fun focusedMessagePreviewDescription(
    author: String,
    body: String,
    mediaLabels: List<String>,
    time: String,
    status: String?,
    warning: String?,
): String =
    (listOf(author, body) + mediaLabels + listOfNotNull(time, status, warning))
        .filter(String::isNotBlank)
        .joinToString(", ")

internal const val FOCUSED_PREVIEW_TEXT_LINES = 5
private const val FOCUSED_PREVIEW_TEXT_BUDGET = 32_000
