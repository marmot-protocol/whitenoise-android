@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.markdownDocumentToPreviewText
import dev.ipf.whitenoise.android.ui.markdownInlinesToAnnotatedString
import dev.ipf.whitenoise.android.ui.previewTake
import kotlin.math.ceil

/** Display-only excerpt keeps native inline styling but removes all actionable link annotations. */
internal fun focusedMessagePreviewText(
    source: String,
    document: MarkdownDocumentFfi?,
    mentionDisplayName: ((String) -> String?)? = null,
    isGroupMember: ((String) -> Boolean)? = null,
): AnnotatedString {
    if (document == null || document.blocks.isEmpty()) return AnnotatedString(source)
    val paragraph = document.blocks.singleOrNull() as? MarkdownBlockFfi.Paragraph
    return if (paragraph != null) {
        val styled =
            markdownInlinesToAnnotatedString(
                inlines = paragraph.inlines,
                codeStyle = SpanStyle(fontFamily = FontFamily.Monospace),
                linkStyle = SpanStyle(textDecoration = TextDecoration.Underline),
                mentionDisplayName = mentionDisplayName,
                isGroupMember = isGroupMember,
                useDecorativeBackgrounds = false,
            )
        val inertLinkStyles =
            styled.getLinkAnnotations(0, styled.length).mapNotNull { range ->
                range.item.styles
                    ?.style
                    ?.let { style -> AnnotatedString.Range(style, range.start, range.end) }
            }
        AnnotatedString(styled.text, styled.spanStyles + inertLinkStyles)
    } else {
        // Multi-block excerpts follow the prototype's plain-text projection, preserving block separation.
        val text =
            buildString {
                for (block in document.blocks) {
                    if (length >= FOCUSED_PREVIEW_TEXT_BUDGET) break
                    if (isNotEmpty()) append("\n\n")
                    val remaining = (FOCUSED_PREVIEW_TEXT_BUDGET - length).coerceAtLeast(0)
                    append(
                        when (block) {
                            is MarkdownBlockFfi.CodeBlock -> block.content.previewTake(remaining)
                            is MarkdownBlockFfi.MathBlock -> block.content.previewTake(remaining)
                            else ->
                                markdownDocumentToPreviewText(
                                    document = document.copy(blocks = listOf(block)),
                                    maxLength = remaining,
                                    mentionDisplayName = mentionDisplayName,
                                )
                        },
                    )
                }
            }
        AnnotatedString(text)
    }
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
    isGroupMember: ((String) -> Boolean)? = null,
    reply: (@Composable () -> Unit)? = null,
    media: (@Composable () -> Unit)? = null,
    warning: String? = null,
    editedLabel: String? = null,
    retention: RetentionIndicatorInput? = null,
    reserveRetentionSpace: Boolean = false,
    mentionedSelf: Boolean = false,
    mentionedYouLabel: String = "",
    footerContent: (@Composable () -> Unit)? = null,
) {
    val excerpt =
        remember(text, document, mentionDisplayName, isGroupMember) {
            focusedMessagePreviewText(text, document, mentionDisplayName, isGroupMember)
        }
    var lastLineWidth by remember(excerpt) { mutableStateOf<Int?>(null) }
    var lastLineBaseline by remember(excerpt) { mutableStateOf<Int?>(null) }
    val footer: @Composable () -> Unit =
        footerContent ?: {
            MessageInlineFooter(
                timeText = time,
                color = colorFromArgb(presentation.contentArgb),
                showStatus = showStatus,
                status = status,
                editedLabel = editedLabel,
                onEditedClick = null,
                retention = retention,
                reserveRetentionSpace = reserveRetentionSpace,
                statusContainerColor = colorFromArgb(presentation.backgroundArgb),
            )
        }
    val content: @Composable () -> Unit = {
        senderName?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        BubbleFooterLayout(
            footer = footer,
            lastLineWidth = lastLineWidth.takeIf { warning == null },
            lastLineBaseline = lastLineBaseline.takeIf { warning == null },
        ) {
            Column {
                Text(
                    text = excerpt,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = FOCUSED_PREVIEW_TEXT_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("message-actions-excerpt"),
                    onTextLayout = { layout ->
                        val lastLine = layout.lineCount - 1
                        lastLineWidth =
                            if (layout.lineCount > 0) ceil(layout.getLineRight(lastLine)).toInt() else null
                        lastLineBaseline =
                            if (layout.lineCount > 0) layout.getLineBaseline(lastLine).toInt() else null
                    },
                )
                warning?.let {
                    MessageBubbleInvalidationWarning(it, color = colorFromArgb(presentation.contentArgb))
                }
            }
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
            reply?.invoke()
            content()
        }
    } else {
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            reply?.invoke()
            MediaCaptionFrame(
                presentation = presentation,
                highlighted = false,
                mine = mine,
                mentionedSelf = mentionedSelf,
                mentionedYouLabel = mentionedYouLabel,
                alignEnd = mine,
                media = { media() },
            ) { content() }
        }
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
