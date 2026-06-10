package dev.ipf.darkmatter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi

/**
 * Compose renderer for the Markdown AST the Rust core attaches to every
 * timeline record (`contentTokens`). MessageBubble routes a message body here
 * whenever its document has blocks; an empty document falls back to the plain
 * `Text` path, so a parse failure (or legacy record) degrades to exactly the
 * old rendering.
 *
 * Color policy: everything derives from [LocalContentColor] (set by the
 * bubble surface) so the same document reads correctly on both the outgoing
 * `primaryContainer` and incoming `surfaceVariant` bubbles — no hardcoded
 * fills. Accents (code background, quote bar, dividers) are alpha tints of
 * the content color rather than scheme tokens because the incoming bubble IS
 * `surfaceVariant`; a token-colored chip would vanish into it.
 *
 * Deliberately not rendered in v1 (all degrade to their literal text):
 * - Inline images: alt text styled as a link to the image URL. Inline remote
 *   fetches would bypass the encrypted-media pipeline, so we don't.
 * - Math (inline + block): monospace literal, no typesetting.
 * - Nostr mentions/URIs: plain bech32 text, not tappable.
 */
@Composable
internal fun MarkdownMessageBody(
    document: MarkdownDocumentFfi,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // One listener for every link in the document; the tapped URL rides in on
    // the annotation itself. Scheme gating lives in openMarkdownLink (and the
    // builder never annotates a non-http(s) destination in the first place).
    val linkListener =
        remember(context) {
            LinkInteractionListener { annotation ->
                (annotation as? LinkAnnotation.Url)?.let { openMarkdownLink(context, it.url) }
            }
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        document.blocks.forEach { block ->
            MarkdownBlockView(block, linkListener)
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlockFfi,
    linkListener: LinkInteractionListener,
) {
    when (block) {
        is MarkdownBlockFfi.Paragraph ->
            Text(
                rememberMarkdownInlineText(block.inlines, linkListener),
                style = MaterialTheme.typography.bodyLarge,
            )
        is MarkdownBlockFfi.Heading ->
            Text(
                rememberMarkdownInlineText(block.inlines, linkListener),
                style = markdownHeadingStyle(block.level.toInt()),
            )
        MarkdownBlockFfi.ThematicBreak ->
            HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.25f))
        is MarkdownBlockFfi.CodeBlock -> MarkdownCodeBlockView(block.content)
        is MarkdownBlockFfi.BlockQuote -> MarkdownBlockQuoteView(block.blocks, linkListener)
        is MarkdownBlockFfi.List -> MarkdownListView(block, linkListener)
        is MarkdownBlockFfi.Table -> MarkdownTableView(block, linkListener)
        // No math typesetting in v1 — show the raw TeX in the code treatment
        // so it at least reads as "source", not as broken prose.
        is MarkdownBlockFfi.MathBlock -> MarkdownCodeBlockView(block.content)
    }
}

@Composable
private fun markdownHeadingStyle(level: Int): TextStyle =
    when (level) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }.copy(fontWeight = FontWeight.SemiBold)

@Composable
private fun MarkdownCodeBlockView(content: String) {
    Text(
        // The parser keeps the block's trailing newline; trimming it avoids a
        // phantom empty line inside the chip.
        content.trimEnd('\n'),
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(LocalContentColor.current.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun MarkdownBlockQuoteView(
    blocks: List<MarkdownBlockFfi>,
    linkListener: LinkInteractionListener,
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(LocalContentColor.current.copy(alpha = 0.35f), RoundedCornerShape(1.5.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { MarkdownBlockView(it, linkListener) }
        }
    }
}

@Composable
private fun MarkdownListView(
    block: MarkdownBlockFfi.List,
    linkListener: LinkInteractionListener,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (block.tight) 2.dp else 6.dp)) {
        block.items.forEachIndexed { index, item ->
            Row {
                Text(
                    // Task-list checkboxes win over the plain bullet/number so
                    // `- [x] done` reads as a checked item, not a bullet.
                    when (item.checked) {
                        true -> "☑"
                        false -> "☐"
                        null -> markdownListMarker(block.kind, index)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.blocks.forEach { MarkdownBlockView(it, linkListener) }
                }
            }
        }
    }
}

/**
 * Simple v1 table: equal-weight columns, a divider under the bolded header
 * row, column alignment honored via [TextAlign]. No per-column intrinsic
 * sizing — acceptable inside a chat bubble's width budget.
 */
@Composable
private fun MarkdownTableView(
    block: MarkdownBlockFfi.Table,
    linkListener: LinkInteractionListener,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MarkdownTableRowView(block.header, block.alignments, header = true, linkListener)
        HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.25f))
        block.rows.forEach { row ->
            MarkdownTableRowView(row, block.alignments, header = false, linkListener)
        }
    }
}

@Composable
private fun MarkdownTableRowView(
    cells: List<MarkdownTableCellFfi>,
    alignments: List<MarkdownAlignmentFfi>,
    header: Boolean,
    linkListener: LinkInteractionListener,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.forEachIndexed { index, cell ->
            Text(
                rememberMarkdownInlineText(cell.inlines, linkListener),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                textAlign =
                    when (alignments.getOrNull(index)) {
                        MarkdownAlignmentFfi.CENTER -> TextAlign.Center
                        MarkdownAlignmentFfi.RIGHT -> TextAlign.End
                        else -> TextAlign.Start
                    },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun rememberMarkdownInlineText(
    inlines: List<MarkdownInlineFfi>,
    linkListener: LinkInteractionListener,
): AnnotatedString {
    val contentColor = LocalContentColor.current
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(inlines, contentColor, linkColor, linkListener) {
        markdownInlinesToAnnotatedString(
            inlines = inlines,
            codeStyle =
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = contentColor.copy(alpha = 0.08f),
                ),
            linkStyle =
                SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
            linkListener = linkListener,
        )
    }
}

/**
 * Pure inline-tree → [AnnotatedString] mapping (kept free of composition so
 * it's unit-testable). Only http/https destinations become tappable
 * [LinkAnnotation.Url]s; anything else (javascript:, file:, nostr:, …)
 * renders its visible text with no annotation at all, so there is nothing to
 * tap and nothing to launch.
 */
internal fun markdownInlinesToAnnotatedString(
    inlines: List<MarkdownInlineFfi>,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    linkListener: LinkInteractionListener? = null,
): AnnotatedString =
    buildAnnotatedString {
        appendMarkdownInlines(inlines, codeStyle, linkStyle, linkListener)
    }

private fun AnnotatedString.Builder.appendMarkdownInlines(
    inlines: List<MarkdownInlineFfi>,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    linkListener: LinkInteractionListener?,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInlineFfi.Text -> append(inline.content)
            // Chat keeps the author's line breaks: a soft break renders as a
            // newline (not the CommonMark collapse-to-space) to match how the
            // plaintext fallback has always displayed.
            MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> append('\n')
            is MarkdownInlineFfi.Code -> withStyle(codeStyle) { append(inline.content) }
            is MarkdownInlineFfi.Emph ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendMarkdownInlines(inline.children, codeStyle, linkStyle, linkListener)
                }
            is MarkdownInlineFfi.Strong ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendMarkdownInlines(inline.children, codeStyle, linkStyle, linkListener)
                }
            is MarkdownInlineFfi.Strikethrough ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendMarkdownInlines(inline.children, codeStyle, linkStyle, linkListener)
                }
            is MarkdownInlineFfi.Link ->
                appendMarkdownLink(inline.dest, inline.children, codeStyle, linkStyle, linkListener)
            // No inline image fetches (they'd bypass the encrypted-media
            // pipeline): the alt text stands in, tappable through to the
            // image URL when it's plain http(s).
            is MarkdownInlineFfi.Image ->
                appendMarkdownLink(inline.dest, inline.alt, codeStyle, linkStyle, linkListener)
            is MarkdownInlineFfi.Autolink ->
                if (inline.kind == MarkdownAutolinkKindFfi.URI && isOpenableMarkdownLink(inline.url)) {
                    withLink(LinkAnnotation.Url(inline.url, TextLinkStyles(style = linkStyle), linkListener)) {
                        append(inline.url)
                    }
                } else {
                    // Email autolinks (and any non-http URI) stay visible but
                    // inert — the only schemes this renderer ever opens are
                    // http/https.
                    append(inline.url)
                }
            is MarkdownInlineFfi.Math -> withStyle(codeStyle) { append(inline.content) }
            // Nostr entities render as their bech32 text for now; routing
            // npubs through presentProfile is a follow-up.
            is MarkdownInlineFfi.NostrMention -> append(inline.entity.bech32)
            is MarkdownInlineFfi.NostrUri -> append(inline.entity.bech32)
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownLink(
    dest: String,
    children: List<MarkdownInlineFfi>,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    linkListener: LinkInteractionListener?,
) {
    // A label-less link (`[](url)` or an image with empty alt) would otherwise
    // produce a zero-length, untappable annotation — show the URL itself.
    val visible = children.ifEmpty { listOf(MarkdownInlineFfi.Text(dest)) }
    if (isOpenableMarkdownLink(dest)) {
        withLink(LinkAnnotation.Url(dest, TextLinkStyles(style = linkStyle), linkListener)) {
            appendMarkdownInlines(visible, codeStyle, linkStyle, linkListener)
        }
    } else {
        appendMarkdownInlines(visible, codeStyle, linkStyle, linkListener)
    }
}

internal fun markdownListMarker(
    kind: MarkdownListKindFfi,
    index: Int,
): String =
    when (kind) {
        is MarkdownListKindFfi.Bullet -> "•"
        is MarkdownListKindFfi.Ordered -> "${kind.start + index.toUInt()}${kind.delimiter}"
    }

/** The only link schemes the markdown renderer will hand to `ACTION_VIEW`. */
internal fun isOpenableMarkdownLink(dest: String): Boolean {
    val trimmed = dest.trim()
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
}

/**
 * Same fire-and-catch pattern as `openAttachmentExternally`: no
 * `resolveActivity` pre-flight (package visibility makes it lie), just catch
 * `ActivityNotFoundException` as the authoritative "no browser" signal and
 * swallow it — a dead tap beats a crash, and devices without any browser are
 * vanishingly rare.
 */
private fun openMarkdownLink(
    context: android.content.Context,
    url: String,
) {
    if (!isOpenableMarkdownLink(url)) return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        // No handler for http(s) — nothing sane to do.
    }
}
