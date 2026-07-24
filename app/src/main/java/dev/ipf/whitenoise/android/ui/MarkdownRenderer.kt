package dev.ipf.whitenoise.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import java.net.IDN
import java.net.URI
import java.util.Locale

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
 *
 * Nostr entities are first-class. The "@" prefix is reserved for a real
 * group-member mention: a `NostrMention` whose resolved account is a current
 * member of the active group (per [isGroupMember]) renders as "@DisplayName"
 * (bold, tinted). A pasted npub/nprofile of a NON-member still resolves and
 * shows its display name via [mentionDisplayName], but WITHOUT the "@" — it
 * reads as an inline profile link, not a mention, so "@" keeps meaning
 * "addressing a member of this group" (#1017). An unresolved entity falls back
 * to its shortened bech32 in the code style. npub/nprofile entities route taps
 * to [onNostrProfileTap] (an in-app profile presentation — identity taps are
 * never handed to external apps via ACTION_VIEW) in every case;
 * note/nevent/naddr/nrelay stay styled but inert.
 */
@Composable
internal fun MarkdownMessageBody(
    document: MarkdownDocumentFfi,
    modifier: Modifier = Modifier,
    mentionDisplayName: ((String) -> String?)? = null,
    // Whether a mention entity's bech32 resolves to a current member of the
    // active group. Only a member gets the "@" mention treatment; a resolved
    // non-member keeps its name but drops the "@" (#1017). Null (no roster
    // available) treats every resolved mention as a member — the pre-#1017
    // behavior — so DM/preview callers without a roster are unchanged.
    isGroupMember: ((String) -> Boolean)? = null,
    onNostrProfileTap: ((String) -> Unit)? = null,
    useDecorativeBackgrounds: Boolean = true,
    // Reports the layout of the final rendered text line so a caller can place
    // an inline footer against it. Fires for a text-bearing last block, or for
    // the elision marker when the top-level block cap hides later siblings;
    // other block types leave it unset.
    onLastTextLayout: ((TextLayoutResult) -> Unit)? = null,
    // Reports every selectable body Text with stable identity plus its latest
    // layout/coordinates. MessageBubble uses this only while partial text
    // selection is active to seed the native selection at the original press.
    onSelectableTextLayoutChanged: SelectableTextLayoutReporter? = null,
    // Link-bearing text leaves report their layout so the bubble's row-level
    // long-press detector can distinguish a URL press from a plain-text press.
    onLinkTextLayoutChanged: MarkdownLinkTextLayoutReporter? = null,
    // Accessibility actions invoke the same copy path without a pointer event.
    onCopyLink: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    // A tapped spoofable `[label](url)` link parks its destination here until
    // the user confirms it in the dialog below (#273).
    var pendingLinkUrl by remember { mutableStateOf<String?>(null) }
    // One listener for every link in the document; the tapped destination rides
    // in on the annotation. Autolink URL annotations open directly because their
    // visible text is the supplied destination; confirm-link Clickable annotations
    // surface destinations hidden behind author-controlled labels first;
    // nostr-profile Clickable annotations stay in-app.
    val linkListener =
        remember(context, onNostrProfileTap) {
            LinkInteractionListener { annotation ->
                when (annotation) {
                    is LinkAnnotation.Url -> openMarkdownLink(context, annotation.url)
                    is LinkAnnotation.Clickable ->
                        when {
                            annotation.tag.startsWith(CONFIRM_LINK_TAG_PREFIX) ->
                                pendingLinkUrl = annotation.tag.removePrefix(CONFIRM_LINK_TAG_PREFIX)
                            annotation.tag.startsWith(NOSTR_PROFILE_LINK_TAG_PREFIX) ->
                                onNostrProfileTap?.invoke(annotation.tag.removePrefix(NOSTR_PROFILE_LINK_TAG_PREFIX))
                            else -> Unit
                        }
                    else -> Unit
                }
            }
        }
    val bodyContext =
        remember(linkListener, mentionDisplayName, isGroupMember, useDecorativeBackgrounds) {
            MarkdownBodyContext(linkListener, mentionDisplayName, isGroupMember, useDecorativeBackgrounds)
        }
    CompositionLocalProvider(
        LocalSelectableTextLayoutReporter provides onSelectableTextLayoutChanged,
        LocalMarkdownLinkTextLayoutReporter provides onLinkTextLayoutChanged,
        LocalMarkdownLinkCopyHandler provides onCopyLink,
    ) {
        MarkdownBlockList(
            blocks = document.blocks,
            ctx = bodyContext,
            depth = 0,
            modifier = modifier,
            onLastTextLayout = onLastTextLayout,
        )
    }
    pendingLinkUrl?.let { url ->
        val parsedLink = remember(url) { parsedOpenableMarkdownLink(url) }
        AlertDialog(
            onDismissRequest = { pendingLinkUrl = null },
            title = { Text(stringResource(R.string.link_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    parsedLink?.effectiveAuthority?.let { authority ->
                        Text(
                            authority,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        markdownSafeDisplayText(parsedLink?.destination ?: url),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLinkUrl = null
                        openMarkdownLink(context, url)
                    },
                ) { Text(stringResource(R.string.link_confirm_open)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLinkUrl = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Maximum block-nesting depth the renderer will descend before it stops
 * recursing. Block quotes and lists render their children via
 * [MarkdownBlockView] again, so a peer-crafted message with thousands of
 * nested quotes/lists would otherwise overflow the stack and crash the app on
 * open (a DoS — the body renders as soon as the conversation is shown). No
 * legitimate chat message nests anywhere near this deep. See #156.
 */
internal const val MARKDOWN_MAX_BLOCK_DEPTH = 24

internal fun markdownDepthExceeded(depth: Int): Boolean = depth >= MARKDOWN_MAX_BLOCK_DEPTH

/**
 * Maximum number of Markdown siblings rendered or walked at any one untrusted
 * container boundary. Depth caps stop recursive stack DoS, but breadth DoS can
 * also hide under one top-level quote/list/table whose children are all depth 1.
 * 256 leaves ample room for legitimate chat formatting while bounding render,
 * mention, and preview work. See #942.
 */
internal const val MARKDOWN_MAX_CONTAINER_SIBLINGS = 256

/** Wide tables are unreadable in a chat bubble and expensive to lay out. */
internal const val MARKDOWN_MAX_TABLE_COLUMNS = 12

/** One table shares a single cell budget across its header and all body rows. */
internal const val MARKDOWN_MAX_TABLE_CELLS = MARKDOWN_MAX_CONTAINER_SIBLINGS

internal fun <T> markdownVisibleSiblings(items: List<T>): List<T> =
    if (items.size <= MARKDOWN_MAX_CONTAINER_SIBLINGS) items else items.take(MARKDOWN_MAX_CONTAINER_SIBLINGS)

internal fun markdownSiblingsElided(items: List<*>): Boolean = items.size > MARKDOWN_MAX_CONTAINER_SIBLINGS

internal data class MarkdownTableRowWindow<T>(
    val cells: List<T>,
    val cellsElided: Boolean,
)

internal data class MarkdownTableWindow<T>(
    val header: MarkdownTableRowWindow<T>,
    val rows: List<MarkdownTableRowWindow<T>>,
    val rowsElided: Boolean,
)

/** Applies one area budget to a table instead of independently capping both dimensions. */
internal fun <T> markdownVisibleTable(
    header: List<T>,
    rows: List<List<T>>,
): MarkdownTableWindow<T> {
    var remainingCells = MARKDOWN_MAX_TABLE_CELLS

    fun visibleRow(cells: List<T>): MarkdownTableRowWindow<T> {
        val visibleCount = minOf(cells.size, MARKDOWN_MAX_TABLE_COLUMNS, remainingCells)
        remainingCells -= visibleCount
        return MarkdownTableRowWindow(
            cells = cells.take(visibleCount),
            cellsElided = visibleCount < cells.size,
        )
    }

    val visibleHeader = visibleRow(header)
    val visibleRows = ArrayList<MarkdownTableRowWindow<T>>()
    val rowLimit = minOf(rows.size, MARKDOWN_MAX_CONTAINER_SIBLINGS)
    for (index in 0 until rowLimit) {
        if (remainingCells <= 0) break
        visibleRows += visibleRow(rows[index])
    }
    return MarkdownTableWindow(
        header = visibleHeader,
        rows = visibleRows,
        rowsElided = visibleRows.size < rows.size,
    )
}

/**
 * Maximum inline-nesting depth. Inline nodes (emphasis, strong, strikethrough,
 * link, image alt) carry child inlines, so the inline walkers recurse too — a
 * peer-crafted tree of repeated nested emphasis/links would overflow the stack
 * or burn CPU just like deep block nesting. Real formatting nests a handful of
 * levels (bold-italic-link); 64 is generous headroom. See #156.
 */
internal const val MARKDOWN_MAX_INLINE_DEPTH = 64

internal fun markdownInlineDepthExceeded(depth: Int): Boolean = depth >= MARKDOWN_MAX_INLINE_DEPTH

internal const val MARKDOWN_LINK_CONFIRM_DISPLAY_MAX_LENGTH = 500

internal fun markdownSafeDisplayText(
    value: String,
    maxLength: Int = MARKDOWN_LINK_CONFIRM_DISPLAY_MAX_LENGTH,
): String {
    val sanitized = ProfileSanitizer.stripUnsafe(value)
    if (sanitized.codePointCount(0, sanitized.length) <= maxLength) return sanitized
    val end = sanitized.offsetByCodePoints(0, maxLength)
    return sanitized.substring(0, end)
}

/** Per-document inputs threaded through every block view. */
private data class MarkdownBodyContext(
    val linkListener: LinkInteractionListener,
    val mentionDisplayName: ((String) -> String?)?,
    val isGroupMember: ((String) -> Boolean)?,
    val useDecorativeBackgrounds: Boolean,
)

internal typealias SelectableTextLayoutReporter =
    (key: Any, layoutResult: TextLayoutResult?, coordinates: LayoutCoordinates?) -> Unit

internal typealias MarkdownLinkTextLayoutReporter =
    (key: Any, text: AnnotatedString, layoutResult: TextLayoutResult?, coordinates: LayoutCoordinates?) -> Unit

internal data class MarkdownLinkTextLayout(
    val text: AnnotatedString,
    val layoutResult: TextLayoutResult,
    val coordinates: LayoutCoordinates,
)

private val LocalSelectableTextLayoutReporter =
    staticCompositionLocalOf<SelectableTextLayoutReporter?> { null }

private val LocalMarkdownLinkTextLayoutReporter =
    staticCompositionLocalOf<MarkdownLinkTextLayoutReporter?> { null }

private val LocalMarkdownLinkCopyHandler =
    staticCompositionLocalOf<((String) -> Unit)?> { null }

private class MarkdownTextLayoutTracker {
    var layoutResult: TextLayoutResult? = null
    var coordinates: LayoutCoordinates? = null
}

/** Text leaf used by the rendered Markdown document (dialog chrome excluded). */
@Composable
private fun MarkdownBodyText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    textAlign: TextAlign? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val reporter = LocalSelectableTextLayoutReporter.current
    val linkReporter = LocalMarkdownLinkTextLayoutReporter.current
    val onCopyLink = LocalMarkdownLinkCopyHandler.current
    val copyLabel = stringResource(R.string.copy)
    val linkDestinations = remember(text) { markdownLinkDestinations(text) }
    val reportsLinks = linkDestinations.isNotEmpty()
    val key = remember { Any() }
    val tracker = remember { MarkdownTextLayoutTracker() }

    DisposableEffect(reporter, linkReporter, key, text) {
        onDispose {
            reporter?.invoke(key, null, null)
            linkReporter?.invoke(key, text, null, null)
        }
    }

    fun reportIfReady() {
        val layoutResult = tracker.layoutResult ?: return
        val coordinates = tracker.coordinates ?: return
        reporter?.invoke(key, layoutResult, coordinates)
        if (reportsLinks) {
            linkReporter?.invoke(key, text, layoutResult, coordinates)
        } else {
            linkReporter?.invoke(key, text, null, null)
        }
    }

    val accessibilityModifier =
        if (onCopyLink == null || linkDestinations.isEmpty()) {
            Modifier
        } else {
            Modifier.semantics {
                customActions =
                    linkDestinations.map { destination ->
                        CustomAccessibilityAction("$copyLabel: ${markdownSafeDisplayText(destination)}") {
                            onCopyLink(destination)
                            true
                        }
                    }
            }
        }

    Text(
        text = text,
        modifier =
            modifier
                .then(accessibilityModifier)
                .onGloballyPositioned { coordinates ->
                    tracker.coordinates = coordinates
                    reportIfReady()
                },
        style = style,
        textAlign = textAlign,
        onTextLayout = { layoutResult ->
            tracker.layoutResult = layoutResult
            onTextLayout?.invoke(layoutResult)
            reportIfReady()
        },
    )
}

internal fun markdownLinkDestinationAt(
    layouts: Collection<MarkdownLinkTextLayout>,
    positionInWindow: Offset,
): String? =
    layouts.firstNotNullOfOrNull { textLayout ->
        if (!textLayout.coordinates.isAttached || !textLayout.coordinates.boundsInWindow().contains(positionInWindow)) {
            null
        } else {
            markdownLinkDestinationAt(
                textLayout.text,
                textLayout.layoutResult,
                textLayout.coordinates.windowToLocal(positionInWindow),
            )
        }
    }

internal fun markdownLinkDestinationAt(
    text: AnnotatedString,
    layoutResult: TextLayoutResult,
    position: Offset,
): String? {
    if (text.isEmpty() || position.y !in 0f..layoutResult.size.height.toFloat()) return null
    val line = layoutResult.getLineForVerticalPosition(position.y)
    val lineLeft = minOf(layoutResult.getLineLeft(line), layoutResult.getLineRight(line))
    val lineRight = maxOf(layoutResult.getLineLeft(line), layoutResult.getLineRight(line))
    if (position.x !in lineLeft..lineRight) return null
    val offset = layoutResult.getOffsetForPosition(position).coerceIn(0, text.lastIndex)
    return text
        .getLinkAnnotations(offset, offset + 1)
        .firstNotNullOfOrNull { range -> markdownLinkDestination(range.item) }
}

internal fun markdownLinkDestinations(text: AnnotatedString): List<String> =
    text
        .getLinkAnnotations(0, text.length)
        .mapNotNull { range -> markdownLinkDestination(range.item) }
        .distinct()

internal fun markdownLinkDestination(annotation: LinkAnnotation): String? =
    when (annotation) {
        is LinkAnnotation.Url -> parsedOpenableMarkdownLink(annotation.url)?.destination
        is LinkAnnotation.Clickable ->
            annotation.tag
                .takeIf { it.startsWith(CONFIRM_LINK_TAG_PREFIX) }
                ?.removePrefix(CONFIRM_LINK_TAG_PREFIX)
                ?.let(::parsedOpenableMarkdownLink)
                ?.destination
        else -> null
    }

@Composable
private fun MarkdownBlockList(
    blocks: List<MarkdownBlockFfi>,
    ctx: MarkdownBodyContext,
    depth: Int,
    modifier: Modifier = Modifier,
    onLastTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val visibleBlocks = markdownVisibleSiblings(blocks)
    val blocksElided = markdownSiblingsElided(blocks)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visibleBlocks.forEachIndexed { index, block ->
            MarkdownBlockView(
                block,
                ctx,
                depth = depth,
                onTextLayout = if (!blocksElided && index == visibleBlocks.lastIndex) onLastTextLayout else null,
            )
        }
        if (blocksElided) {
            MarkdownElisionMarker(onTextLayout = onLastTextLayout)
        }
    }
}

@Composable
private fun MarkdownElisionMarker(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    MarkdownBodyText(
        text = AnnotatedString("…"),
        style = style,
        modifier = modifier,
        onTextLayout = onTextLayout,
    )
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlockFfi,
    ctx: MarkdownBodyContext,
    depth: Int,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    // Past the nesting cap, stop descending: render a plain ellipsis marker
    // instead of recursing into another quote/list level. Bounds the render
    // stack against a maliciously deep document. See #156.
    if (markdownDepthExceeded(depth)) {
        MarkdownElisionMarker()
        return
    }
    when (block) {
        is MarkdownBlockFfi.Paragraph -> {
            val details = remember(block) { markdownDetailsSection(block.inlines) }
            if (details == null) {
                MarkdownBodyText(
                    text = rememberMarkdownInlineText(block.inlines, ctx),
                    style = MaterialTheme.typography.bodyLarge,
                    onTextLayout = onTextLayout,
                )
            } else {
                // A collapsible has no stable trailing text line, so the
                // inline-footer callback stays unset like other non-text blocks.
                MarkdownDetailsView(details, ctx)
            }
        }
        is MarkdownBlockFfi.Heading ->
            MarkdownBodyText(
                text = rememberMarkdownInlineText(block.inlines, ctx),
                style = markdownHeadingTextStyle(block.level.toInt(), MaterialTheme.typography),
                onTextLayout = onTextLayout,
            )
        MarkdownBlockFfi.ThematicBreak ->
            HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.25f))
        is MarkdownBlockFfi.CodeBlock -> MarkdownCodeBlockView(block.content, ctx.useDecorativeBackgrounds)
        is MarkdownBlockFfi.BlockQuote -> MarkdownBlockQuoteView(block.blocks, ctx, depth)
        is MarkdownBlockFfi.ListBlock -> MarkdownListView(block, ctx, depth)
        is MarkdownBlockFfi.Table -> MarkdownTableView(block, ctx)
        // No math typesetting in v1 — show the raw TeX in the code treatment
        // so it at least reads as "source", not as broken prose.
        is MarkdownBlockFfi.MathBlock -> MarkdownCodeBlockView(block.content, ctx.useDecorativeBackgrounds)
    }
}

/**
 * Six distinct, strictly descending heading tiers, all SemiBold, sized for a
 * chat bubble: the ramp tops out at headlineSmall (a bubble is not a document
 * page) and bottoms out at body sizes where only the weight separates H5/H6
 * from prose. H3/H4 derive from titleLarge with explicit 20/18sp sizes
 * because the M3 token scale has no monotonic steps between titleLarge (22)
 * and bodyLarge (16) — titleMedium/titleSmall collide with the body sizes.
 * Out-of-range levels clamp to the smallest tier. Pure so the ramp is
 * unit-testable.
 */
internal fun markdownHeadingTextStyle(
    level: Int,
    typography: Typography,
): TextStyle =
    when (level) {
        1 -> typography.headlineSmall
        2 -> typography.titleLarge
        3 -> typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp)
        4 -> typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp)
        5 -> typography.bodyLarge
        else -> typography.bodyMedium
    }.copy(fontWeight = FontWeight.SemiBold)

@Composable
private fun MarkdownCodeBlockView(
    content: String,
    useDecorativeBackground: Boolean,
) {
    // The parser keeps the block's trailing newline; trimming it avoids a
    // phantom empty line inside the chip. Code/math blocks can be large, so
    // cache the sanitization work instead of repeating it on every recomposition.
    val text = remember(content) { markdownSafeDisplayText(content, Int.MAX_VALUE).trimEnd('\n') }

    MarkdownBodyText(
        text = AnnotatedString(text),
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (useDecorativeBackground) LocalContentColor.current.copy(alpha = 0.08f) else Color.Transparent,
                    RoundedCornerShape(8.dp),
                ).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** One recognized `<details>` disclosure: optional summary label + the collapsed inline content. */
internal data class MarkdownDetailsSection(
    val summary: String?,
    val content: List<MarkdownInlineFfi>,
)

// Opening line: bare `<details>`, optionally carrying the `<summary>…</summary>` on the same line.
private val DETAILS_OPEN_LINE = Regex("(?i)^<details>\\s*(?:<summary>(.*)</summary>)?$")

// A `<summary>…</summary>` line of its own directly after the opening tag.
private val DETAILS_SUMMARY_LINE = Regex("(?i)^<summary>(.*)</summary>$")

private const val DETAILS_CLOSE_TAG = "</details>"

private fun isMarkdownLineBreak(inline: MarkdownInlineFfi): Boolean =
    when (inline) {
        MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> true
        else -> false
    }

/**
 * Detects a GitHub-style `<details>`/`<summary>` disclosure written as one
 * paragraph. The engine's markdown parser does not recognize HTML, so the
 * markup arrives as literal per-line [MarkdownInlineFfi.Text] runs separated
 * by soft breaks; this matches that shape: an opening `<details>` line
 * (optionally carrying the summary), an optional `<summary>…</summary>` line
 * of its own, the hidden inline content, and a closing `</details>` line.
 * Anything else — no closing tag, tags sharing a line with content — is not a
 * disclosure and renders as the literal text it is.
 *
 * Known limitation: a blank line, or a construct that interrupts a paragraph
 * (list, heading, fence), inside the markup splits it across blocks; cross-block
 * reassembly is not attempted, so those documents render literally too.
 */
internal fun markdownDetailsSection(inlines: List<MarkdownInlineFfi>): MarkdownDetailsSection? {
    val nodes = markdownVisibleSiblings(inlines)
    val openMatch =
        (nodes.firstOrNull() as? MarkdownInlineFfi.Text)
            ?.content
            ?.trim()
            ?.let { DETAILS_OPEN_LINE.matchEntire(it) }
    val closed =
        nodes.size > 1 &&
            (nodes.last() as? MarkdownInlineFfi.Text)?.content?.trim().equals(DETAILS_CLOSE_TAG, ignoreCase = true)
    if (openMatch == null || !closed) return null
    var summary = openMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
    var content = nodes.subList(1, nodes.size - 1).dropWhile(::isMarkdownLineBreak).dropLastWhile(::isMarkdownLineBreak)
    if (summary == null && openMatch.groups[1] == null) {
        val summaryLine =
            (content.firstOrNull() as? MarkdownInlineFfi.Text)
                ?.content
                ?.trim()
                ?.let { DETAILS_SUMMARY_LINE.matchEntire(it) }
        if (summaryLine != null) {
            summary = summaryLine.groupValues[1].trim().takeIf { it.isNotEmpty() }
            content = content.drop(1).dropWhile(::isMarkdownLineBreak)
        }
    }
    return MarkdownDetailsSection(summary, content)
}

private val DETAILS_CHEVRON_SIZE = 20.dp
private val DETAILS_CONTENT_INDENT = 24.dp
private const val DETAILS_CHEVRON_COLLAPSED_DEGREES = -90f
private const val DETAILS_CHEVRON_EXPANDED_DEGREES = 0f

/**
 * Header row (chevron + summary) toggling the hidden content, collapsed by
 * default. Expansion state lives in the composition only — scrolling the
 * message away and back resets to collapsed, which is acceptable for a chat
 * bubble. The content keeps the full inline treatment (formatting, links,
 * mentions) since it is the same paragraph's inline run.
 */
@Suppress("FunctionNaming")
@Composable
private fun MarkdownDetailsView(
    section: MarkdownDetailsSection,
    ctx: MarkdownBodyContext,
) {
    var expanded by remember(section) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) DETAILS_CHEVRON_EXPANDED_DEGREES else DETAILS_CHEVRON_COLLAPSED_DEGREES,
        label = "detailsChevron",
    )
    val summaryText = section.summary?.let { markdownSafeDisplayText(it) } ?: stringResource(R.string.details)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(DETAILS_CHEVRON_SIZE)
                        .rotate(chevronRotation),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                summaryText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            MarkdownBodyText(
                text = rememberMarkdownInlineText(section.content, ctx),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = DETAILS_CONTENT_INDENT, top = 2.dp),
            )
        }
    }
}

@Composable
private fun MarkdownBlockQuoteView(
    blocks: List<MarkdownBlockFfi>,
    ctx: MarkdownBodyContext,
    depth: Int,
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(LocalContentColor.current.copy(alpha = 0.35f), RoundedCornerShape(1.5.dp)),
        )
        Spacer(Modifier.width(8.dp))
        // weight(1f) gives the quoted content a bounded width so fillMaxWidth
        // children (nested code blocks) don't measure under unbounded
        // constraints inside the IntrinsicSize.Min row.
        MarkdownBlockList(
            blocks = blocks,
            ctx = ctx,
            depth = depth + 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MarkdownListView(
    block: MarkdownBlockFfi.ListBlock,
    ctx: MarkdownBodyContext,
    depth: Int,
) {
    val visibleItems = markdownVisibleSiblings(block.items)
    val itemsElided = markdownSiblingsElided(block.items)
    Column(verticalArrangement = Arrangement.spacedBy(if (block.tight) 2.dp else 6.dp)) {
        visibleItems.forEachIndexed { index, item ->
            Row {
                MarkdownBodyText(
                    // Task-list checkboxes win over the plain bullet/number so
                    // `- [x] done` reads as a checked item, not a bullet.
                    text =
                        AnnotatedString(
                            when (item.checked) {
                                true -> "☑"
                                false -> "☐"
                                null -> markdownListMarker(block.kind, index)
                            },
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(end = 6.dp),
                )
                MarkdownBlockList(
                    blocks = item.blocks,
                    ctx = ctx,
                    depth = depth + 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (itemsElided) {
            MarkdownElisionMarker()
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
    ctx: MarkdownBodyContext,
) {
    val visibleTable = markdownVisibleTable(block.header, block.rows)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MarkdownTableRowView(visibleTable.header, block.alignments, header = true, ctx)
        HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.25f))
        visibleTable.rows.forEach { row ->
            MarkdownTableRowView(row, block.alignments, header = false, ctx)
        }
        if (visibleTable.rowsElided) {
            MarkdownElisionMarker()
        }
    }
}

@Composable
private fun MarkdownTableRowView(
    row: MarkdownTableRowWindow<MarkdownTableCellFfi>,
    alignments: List<MarkdownAlignmentFfi>,
    header: Boolean,
    ctx: MarkdownBodyContext,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.cells.forEachIndexed { index, cell ->
            MarkdownBodyText(
                text = rememberMarkdownInlineText(cell.inlines, ctx),
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
        if (row.cellsElided) {
            MarkdownElisionMarker(
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun rememberMarkdownInlineText(
    inlines: List<MarkdownInlineFfi>,
    ctx: MarkdownBodyContext,
): AnnotatedString {
    val contentColor = LocalContentColor.current
    // Collecting mentions is a structural O(nodes) walk; keep that keyed only on
    // the parsed inline tree. Resolving names still happens during composition so
    // the resolver can subscribe to profile state and invalidate the rendered
    // string when a profile arrives.
    val mentionBech32s = remember(inlines) { markdownInlineMentionBech32s(inlines) }
    val mentionNames = resolveMentionNames(mentionBech32s, ctx.mentionDisplayName)
    // Links must derive from the content color like every other accent:
    // colorScheme.primary disappears on the outgoing bubble, whose container
    // IS primary. Underline alone carries the affordance on both surfaces.
    return remember(inlines, contentColor, ctx, mentionNames) {
        markdownInlinesToAnnotatedString(
            inlines = inlines,
            codeStyle =
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background =
                        if (ctx.useDecorativeBackgrounds) {
                            contentColor.copy(alpha = 0.08f)
                        } else {
                            Color.Unspecified
                        },
                ),
            linkStyle =
                SpanStyle(
                    color = contentColor,
                    textDecoration = TextDecoration.Underline,
                ),
            linkListener = ctx.linkListener,
            mentionDisplayName = mentionNames::get,
            isGroupMember = ctx.isGroupMember,
            useDecorativeBackgrounds = ctx.useDecorativeBackgrounds,
        )
    }
}

/** Mention bech32 → resolved display name (or null) for one inline tree. */
private fun resolveMentionNames(
    bech32s: Set<String>,
    resolve: ((String) -> String?)?,
): Map<String, String?> {
    if (resolve == null) return emptyMap()
    return bech32s.associateWith(resolve)
}

internal fun markdownInlineMentionBech32s(inlines: List<MarkdownInlineFfi>): Set<String> =
    mutableSetOf<String>()
        .also { collectMentionBech32s(inlines, it, depth = 0) }

private fun collectMentionBech32s(
    inlines: List<MarkdownInlineFfi>,
    out: MutableSet<String>,
    depth: Int,
) {
    if (markdownInlineDepthExceeded(depth)) return
    markdownVisibleSiblings(inlines).forEach { inline ->
        when (inline) {
            is MarkdownInlineFfi.NostrMention -> out += inline.entity.bech32
            is MarkdownInlineFfi.Emph -> collectMentionBech32s(inline.children, out, depth + 1)
            is MarkdownInlineFfi.Strong -> collectMentionBech32s(inline.children, out, depth + 1)
            is MarkdownInlineFfi.Strikethrough -> collectMentionBech32s(inline.children, out, depth + 1)
            is MarkdownInlineFfi.Link -> collectMentionBech32s(inline.children, out, depth + 1)
            is MarkdownInlineFfi.Image -> collectMentionBech32s(inline.alt, out, depth + 1)
            else -> Unit
        }
    }
}

private fun collectBlockMentionBech32s(
    blocks: List<MarkdownBlockFfi>,
    out: MutableSet<String>,
    depth: Int,
) {
    if (markdownDepthExceeded(depth)) return
    markdownVisibleSiblings(blocks).forEach { block ->
        when (block) {
            is MarkdownBlockFfi.Paragraph -> collectMentionBech32s(block.inlines, out, depth = 0)
            is MarkdownBlockFfi.Heading -> collectMentionBech32s(block.inlines, out, depth = 0)
            is MarkdownBlockFfi.BlockQuote -> collectBlockMentionBech32s(block.blocks, out, depth + 1)
            is MarkdownBlockFfi.ListBlock ->
                markdownVisibleSiblings(block.items).forEach { collectBlockMentionBech32s(it.blocks, out, depth + 1) }
            is MarkdownBlockFfi.Table -> {
                val visibleTable = markdownVisibleTable(block.header, block.rows)
                visibleTable.header.cells.forEach { cell -> collectMentionBech32s(cell.inlines, out, depth = 0) }
                visibleTable.rows.forEach { row ->
                    row.cells.forEach { cell -> collectMentionBech32s(cell.inlines, out, depth = 0) }
                }
            }
            else -> Unit
        }
    }
}

internal fun markdownDocumentMentionBech32s(document: MarkdownDocumentFfi): Set<String> =
    mutableSetOf<String>()
        .also { collectBlockMentionBech32s(document.blocks, it, depth = 0) }

/**
 * True when [document] contains a `NostrMention` that resolves to
 * [accountIdHex] — i.e. the current account was @-mentioned in the message.
 * The receiver's bubble uses this to paint the "you were mentioned" treatment
 * (#414) so a self-mention is spottable while scrolling.
 *
 * [resolveAccountIdHex] maps a mention's bech32 (npub/nprofile) to its hex
 * pubkey via the FFI; it's passed in (rather than called here) to keep this a
 * pure, unit-testable walk over the parsed document. A null/blank
 * [accountIdHex] (signed out) is never a match. Comparison is
 * case-insensitive because hex pubkeys round-trip through the FFI in either
 * case.
 */
internal fun documentMentionsAccount(
    document: MarkdownDocumentFfi,
    accountIdHex: String?,
    resolveAccountIdHex: (String) -> String?,
): Boolean {
    val self = accountIdHex?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    return markdownDocumentMentionBech32s(document).any { bech32 ->
        resolveAccountIdHex(bech32)?.trim()?.lowercase() == self
    }
}

/**
 * [LinkAnnotation.Clickable] tag prefix for nostr profile entities
 * (npub/nprofile). These deliberately do NOT become [LinkAnnotation.Url]s:
 * an identity tap must stay in-app (profile sheet), never fan out to whatever
 * external app claims the nostr: scheme.
 */
internal const val NOSTR_PROFILE_LINK_TAG_PREFIX = "nostr-profile:"

// `[label](url)` links (and images) carry an attacker-chosen label over a
// possibly-different destination, so their taps route through a confirmation
// that surfaces the real URL before leaving the app (anti-phishing, #273).
// Autolinks show their supplied destination instead of an attacker-chosen label
// and open directly.
internal const val CONFIRM_LINK_TAG_PREFIX = "confirm-link:"

/**
 * Pure inline-tree → [AnnotatedString] mapping (kept free of composition so
 * it's unit-testable). Only allowlisted destinations (see
 * [isOpenableMarkdownLink]) become tappable [LinkAnnotation.Url]s; anything
 * else (javascript:, data:, file:, …) renders its visible text with no
 * annotation at all, so there is nothing to tap and nothing to launch.
 * A resolved [MarkdownInlineFfi.NostrMention] renders as the bold "@Name"
 * mention only when [isGroupMember] reports its bech32 as a current member of
 * the active group; a resolved non-member keeps its display name but drops the
 * "@" and reads as an inline profile link (#1017). A null [isGroupMember]
 * treats every resolved mention as a member (the pre-#1017 behavior), so
 * roster-less callers are unaffected. Unresolved mentions fall back to their
 * shortened bech32 in [codeStyle].
 */
internal fun markdownInlinesToAnnotatedString(
    inlines: List<MarkdownInlineFfi>,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    linkListener: LinkInteractionListener? = null,
    mentionDisplayName: ((String) -> String?)? = null,
    isGroupMember: ((String) -> Boolean)? = null,
    useDecorativeBackgrounds: Boolean = true,
): AnnotatedString =
    buildAnnotatedString {
        appendMarkdownInlines(
            inlines,
            MarkdownInlineRenderContext(
                codeStyle,
                linkStyle,
                linkListener,
                mentionDisplayName,
                isGroupMember,
                useDecorativeBackgrounds,
            ),
            depth = 0,
        )
    }

/** Immutable bundle threaded through the recursive inline walk. */
private class MarkdownInlineRenderContext(
    val codeStyle: SpanStyle,
    val linkStyle: SpanStyle,
    val linkListener: LinkInteractionListener?,
    val mentionDisplayName: ((String) -> String?)?,
    val isGroupMember: ((String) -> Boolean)?,
    val useDecorativeBackgrounds: Boolean,
)

private fun AnnotatedString.Builder.appendMarkdownInlines(
    inlines: List<MarkdownInlineFfi>,
    ctx: MarkdownInlineRenderContext,
    depth: Int,
) {
    // Bound inline recursion (nested emphasis/strong/link/image) against a
    // peer-crafted deep tree, mirroring the block-depth cap. See #156.
    if (markdownInlineDepthExceeded(depth)) return
    markdownVisibleSiblings(inlines).forEach { inline ->
        when (inline) {
            is MarkdownInlineFfi.Text -> append(markdownSafeDisplayText(inline.content, Int.MAX_VALUE))
            // Chat keeps the author's line breaks: a soft break renders as a
            // newline (not the CommonMark collapse-to-space) to match how the
            // plaintext fallback has always displayed.
            MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> append('\n')
            is MarkdownInlineFfi.Code -> withStyle(ctx.codeStyle) { append(markdownSafeDisplayText(inline.content, Int.MAX_VALUE)) }
            is MarkdownInlineFfi.Emph ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendMarkdownInlines(inline.children, ctx, depth + 1)
                }
            is MarkdownInlineFfi.Strong ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendMarkdownInlines(inline.children, ctx, depth + 1)
                }
            is MarkdownInlineFfi.Strikethrough ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendMarkdownInlines(inline.children, ctx, depth + 1)
                }
            is MarkdownInlineFfi.Link ->
                appendMarkdownLink(inline.dest, inline.children, ctx, depth + 1)
            // No inline image fetches (they'd bypass the encrypted-media
            // pipeline): the alt text stands in, tappable through to the
            // image URL when its scheme is allowlisted.
            is MarkdownInlineFfi.Image ->
                appendMarkdownLink(inline.dest, inline.alt, ctx, depth + 1)
            is MarkdownInlineFfi.Autolink -> {
                // Normalize at the boundary: the gate, the annotation, and the
                // eventual ACTION_VIEW all see the same trimmed destination.
                // A bare email autolink opens through mailto: (the visible
                // text stays the plain address).
                val trimmed = inline.url.trim()
                val dest =
                    if (inline.kind == MarkdownAutolinkKindFfi.EMAIL &&
                        !trimmed.startsWith("mailto:", ignoreCase = true)
                    ) {
                        "mailto:$trimmed"
                    } else {
                        trimmed
                    }
                val parsedLink = parsedOpenableMarkdownLink(dest)
                if (parsedLink != null) {
                    withLink(LinkAnnotation.Url(parsedLink.destination, TextLinkStyles(style = ctx.linkStyle), ctx.linkListener)) {
                        append(markdownSafeDisplayText(inline.url, Int.MAX_VALUE))
                    }
                } else {
                    // Non-allowlisted URIs stay visible but inert.
                    append(markdownSafeDisplayText(inline.url, Int.MAX_VALUE))
                }
            }
            is MarkdownInlineFfi.Math -> withStyle(ctx.codeStyle) { append(markdownSafeDisplayText(inline.content, Int.MAX_VALUE)) }
            is MarkdownInlineFfi.NostrMention -> appendNostrEntity(inline.entity, mention = true, ctx)
            is MarkdownInlineFfi.NostrUri -> appendNostrEntity(inline.entity, mention = false, ctx)
        }
    }
}

/**
 * A group-member mention → "@DisplayName" (bold, tinted). A resolved
 * non-member reference (pasted npub/nprofile of someone outside the active
 * group) → its display name WITHOUT the "@" (underlined link styling), so the
 * "@" stays reserved for actually addressing a member (#1017). An unresolved
 * profile mention keeps the "@" only when no roster is available or the roster
 * snapshot says it is a member; known non-member misses render as shortened
 * bech32 without the "@" so cache misses do not leak a false mention signal.
 * A plain nostr: URI always renders as bare shortened bech32. npub/nprofile
 * entities carry a [LinkAnnotation.Clickable] routed (via the shared listener)
 * to the in-app profile sheet in every resolved/unresolved case; the other HRPs
 * (note/nevent/naddr/nrelay) have no in-app destination yet, so they stay inert.
 */
private fun AnnotatedString.Builder.appendNostrEntity(
    entity: MarkdownNostrEntityFfi,
    mention: Boolean,
    ctx: MarkdownInlineRenderContext,
) {
    val name = if (mention) ctx.mentionDisplayName?.invoke(entity.bech32) else null
    val opensProfile =
        entity.hrp == MarkdownNostrHrpFfi.NPUB || entity.hrp == MarkdownNostrHrpFfi.NPROFILE
    // The "@" is a group-membership signal for profile mentions: apply it (and
    // the bold mention treatment) only when the resolved account is a member of
    // the roster snapshot. A null resolver means no roster is available, so keep
    // the pre-#1017 behavior and treat profile mentions as members. Known
    // non-members drop the prefix even when the profile name is not cached yet.
    // Non-profile NostrMention nodes keep their historical "@" fallback because
    // they do not resolve to accounts/roster seats.
    val mentionIsMember =
        mention &&
            (!opensProfile || (ctx.isGroupMember?.invoke(entity.bech32) ?: true))
    val memberMention = name != null && mentionIsMember
    // The annotated run borrows the link color (LocalContentColor in the
    // bubble): a Clickable region is painted with ITS OWN TextLinkStyles —
    // when those are null, Material's Text falls back to the theme's default
    // link color (primary), which is invisible on the outgoing
    // primary-container bubble. Same color policy as linkStyle itself.
    //
    // A resolved member mention also gets a slight background tint (#414) so it
    // reads as a highlighted token, not just bold text. The tint is an alpha
    // wash of the same content-derived link color rather than a scheme token,
    // so it stays visible on both the incoming surfaceVariant and outgoing
    // primaryContainer bubbles (a token fill would vanish into one of them).
    // A resolved non-member name reads as an inline profile link (underline),
    // not a mention: same content-derived color, no "@", no bold/tint.
    val style =
        when {
            memberMention ->
                SpanStyle(
                    color = ctx.linkStyle.color,
                    fontWeight = FontWeight.Bold,
                    background =
                        if (ctx.useDecorativeBackgrounds) {
                            ctx.linkStyle.color.copy(alpha = 0.12f)
                        } else {
                            Color.Unspecified
                        },
                )
            name != null -> ctx.linkStyle
            else -> ctx.codeStyle.copy(color = ctx.linkStyle.color)
        }
    val visible =
        when {
            memberMention -> "@$name"
            name != null -> name
            else -> (if (mentionIsMember) "@" else "") + shortenedBech32(entity.bech32)
        }
    if (opensProfile) {
        withLink(
            LinkAnnotation.Clickable(
                tag = NOSTR_PROFILE_LINK_TAG_PREFIX + entity.bech32,
                styles = TextLinkStyles(style = style),
                linkInteractionListener = ctx.linkListener,
            ),
        ) {
            // Keep the span on the text too so flattened copies (and the
            // pure-mapping tests) see the styling without the annotation.
            withStyle(style) { append(visible) }
        }
    } else {
        // Inert entities inherit the surrounding color normally — no
        // annotation, no color override needed.
        withStyle(if (memberMention) SpanStyle(fontWeight = FontWeight.Bold) else ctx.codeStyle) {
            append(visible)
        }
    }
}

/**
 * `npub1qqqq…qqqq` style truncation for bech32 entities: first 12 + ellipsis
 * + last 6, leaving short strings untouched. 12 leading characters keep the
 * HRP plus a recognizable run of the body even for `nprofile1`.
 */
internal fun shortenedBech32(bech32: String): String {
    val trimmed = bech32.trim()
    if (trimmed.length <= 19) return trimmed
    return trimmed.take(12) + "…" + trimmed.takeLast(6)
}

private const val PLAINTEXT_BECH32_BODY_CHARS = "ac-hj-np-z02-9"
private const val PLAINTEXT_NPUB = "npub1[$PLAINTEXT_BECH32_BODY_CHARS]{58}"
private const val PLAINTEXT_NPROFILE = "nprofile1[$PLAINTEXT_BECH32_BODY_CHARS]+"
private const val PLAINTEXT_RELAY_HINT_SUFFIX = "\\?relay=\\S+"

// The suffix match consumes through the next whitespace, so sentence punctuation
// after a relay hint is captured too. These characters are restored after the
// hint is dropped from the rendered mention text.
private const val PLAINTEXT_RELAY_HINT_TRAILING_PUNCTUATION = ".,;:!?)]}"

// `@npub1…`, `nostr:npub1…`, and desktop/NIP-27 `nostr:nprofile1…` runs as
// they appear in raw engine plaintext. npub bodies have a fixed 58-char bech32
// payload; nprofile bodies are TLV-shaped and variable length, so validation is
// left to the resolver rather than the regex. A `?relay=…` query appended to a
// profile reference is a relay hint for the same account; it is ignored for
// display/lookup while preserving any sentence punctuation after the hint.
private val PLAINTEXT_PROFILE_MENTION =
    Regex(
        "(@|nostr:)((?:$PLAINTEXT_NPUB)|(?:$PLAINTEXT_NPROFILE))(?:$PLAINTEXT_RELAY_HINT_SUFFIX)?",
        RegexOption.IGNORE_CASE,
    )

/**
 * Resolves `@npub1…`, `nostr:npub1…`, and `nostr:nprofile1…` mentions in raw
 * engine plaintext to `@<display name>`, falling back to `@<shortened bech32>`
 * when [resolver] returns null — matching the message bubble's mention
 * rendering exactly. Used by surfaces that show plaintext without the markdown
 * renderer (reply preview, forward preview). Non-mention text is left untouched.
 */
internal fun resolveMentionsInPlaintext(
    text: String,
    resolver: ((String) -> String?)?,
): String =
    PLAINTEXT_PROFILE_MENTION.replace(ProfileSanitizer.stripUnsafe(text)) { match ->
        val bech32 = match.groupValues[2]
        val relayHintSuffix = match.value.removePrefix(match.groupValues[1] + bech32)
        val trailingPunctuation =
            relayHintSuffix
                .takeIf { it.startsWith("?relay=", ignoreCase = true) }
                ?.takeLastWhile { it in PLAINTEXT_RELAY_HINT_TRAILING_PUNCTUATION }
                .orEmpty()
        val visible = resolver?.invoke(bech32) ?: shortenedBech32(bech32)
        "@$visible$trailingPunctuation"
    }

private fun AnnotatedString.Builder.appendMarkdownLink(
    dest: String,
    children: List<MarkdownInlineFfi>,
    ctx: MarkdownInlineRenderContext,
    depth: Int,
) {
    // Normalize once at the boundary so the openability gate, the stored
    // annotation, and the eventual ACTION_VIEW all agree on the same string.
    // A whitespace-padded URL would otherwise pass the (trimming) gate but
    // lose its scheme in Uri.parse(" https://…").
    val normalizedDest = dest.trim()
    // A label-less link (`[](url)` or an image with empty alt) would otherwise
    // produce a zero-length, untappable annotation — show the URL itself.
    val visible = children.ifEmpty { listOf(MarkdownInlineFfi.Text(normalizedDest)) }
    val parsedLink = parsedOpenableMarkdownLink(normalizedDest)
    if (parsedLink != null) {
        // The label is attacker-chosen, but only a URL-shaped label can
        // misrepresent where the tap goes (issue #273's example). Plain-word
        // labels open directly like autolinks; a label carrying host-shaped
        // text that doesn't match the destination routes through the
        // confirmation dialog that shows the real URL.
        if (shouldConfirmMarkdownLink(markdownInlinePlainText(visible), parsedLink.destination)) {
            withLink(
                LinkAnnotation.Clickable(
                    CONFIRM_LINK_TAG_PREFIX + parsedLink.destination,
                    TextLinkStyles(style = ctx.linkStyle),
                    ctx.linkListener,
                ),
            ) {
                appendMarkdownInlines(visible, ctx, depth)
            }
        } else {
            withLink(
                LinkAnnotation.Url(parsedLink.destination, TextLinkStyles(style = ctx.linkStyle), ctx.linkListener),
            ) {
                appendMarkdownInlines(visible, ctx, depth)
            }
        }
    } else {
        appendMarkdownInlines(visible, ctx, depth)
    }
}

// Domain-shaped token inside a link label: two or more dot-separated parts
// with an alphabetic final part of ≥2 chars, or an IPv4 literal. Deliberately
// loose — a dotted label that merely looks like a host still confirms; only
// labels with no URL-like content at all skip the dialog.
private val LABEL_HOST_TOKEN = Regex("(?i)\\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}\\b")
private val LABEL_IPV4_TOKEN = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b")

// IPv6 shapes: full form (three-plus hex groups), "::" compressions, and
// bracketed literals. Not parsed for comparison — presence alone confirms;
// a code-snippet false positive only costs one dialog, the safe direction.
private val LABEL_IPV6_TOKEN =
    Regex("(?i)(?:\\b[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){2,}\\b|[0-9a-f]{0,4}::[0-9a-f:]*[0-9a-f]|\\[[0-9a-f:.]+\\])")
private val DOT_LIKE_CHARS = charArrayOf('.', '。', '．', '｡')
private const val ASCII_MAX_CODE = 0x7F

/**
 * Whether a labeled markdown link must route through the destination
 * confirmation dialog (#273): true when the visible [labelText] carries
 * host-shaped content that does not match [destination]'s host. A label with
 * no URL-like content has no destination to misrepresent, so confirming it
 * buys nothing. Non-http(s) destinations (mailto) keep confirming — an
 * address-shaped label can misrepresent those too, and they're rare.
 */
internal fun shouldConfirmMarkdownLink(
    labelText: String,
    destination: String,
): Boolean {
    val destHost =
        runCatching { URI(destination) }
            .getOrNull()
            ?.host
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("www.")
            ?: return true
    // Compare what the USER SEES, not the raw tokens: rendering strips bidi
    // and invisible default-ignorable characters, so the scan must run on the
    // same sanitized text — otherwise a zero-width character inside
    // "bank.example" hides the host from the regex while the rendered label
    // still reads as the host (fail-open). A label whose sanitized form
    // differs from the raw text was carrying exactly such characters inside
    // a link label: treat it as spoof-shaped outright.
    val visibleLabel = ProfileSanitizer.stripUnsafe(labelText)
    val carriedInvisibleChars = visibleLabel != labelText
    // A non-ASCII label containing any dot-like character can be a homoglyph
    // host (IDN lookalike) the ASCII token scan below cannot compare — those
    // always keep the dialog. IPv6-shaped labels are likewise incomparable
    // against a hostname and keep it too.
    val idnShapedLabel =
        visibleLabel.any { it.code > ASCII_MAX_CODE } && visibleLabel.any { it in DOT_LIKE_CHARS }
    val labelHosts =
        (LABEL_HOST_TOKEN.findAll(visibleLabel) + LABEL_IPV4_TOKEN.findAll(visibleLabel))
            .map { it.value.lowercase(Locale.ROOT).removePrefix("www.") }
    return carriedInvisibleChars ||
        idnShapedLabel ||
        LABEL_IPV6_TOKEN.containsMatchIn(visibleLabel) ||
        labelHosts.any { host -> host != destHost && !destHost.endsWith(".$host") }
}

/** Flattened visible text of an inline run, for label-vs-destination checks. */
internal fun markdownInlinePlainText(inlines: List<MarkdownInlineFfi>): String =
    buildString {
        fun walk(nodes: List<MarkdownInlineFfi>) {
            nodes.forEach { inline ->
                when (inline) {
                    is MarkdownInlineFfi.Text -> append(inline.content)
                    is MarkdownInlineFfi.Code -> append(inline.content)
                    is MarkdownInlineFfi.Math -> append(inline.content)
                    is MarkdownInlineFfi.Autolink -> append(inline.url)
                    is MarkdownInlineFfi.Emph -> walk(inline.children)
                    is MarkdownInlineFfi.Strong -> walk(inline.children)
                    is MarkdownInlineFfi.Strikethrough -> walk(inline.children)
                    is MarkdownInlineFfi.Link -> walk(inline.children)
                    is MarkdownInlineFfi.Image -> walk(inline.alt)
                    MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> append(' ')
                    is MarkdownInlineFfi.NostrMention, is MarkdownInlineFfi.NostrUri -> Unit
                }
            }
        }
        walk(inlines)
    }

/**
 * Chat-list previews cap the flattened string here: the row is one ellipsized
 * line, so anything past a couple hundred characters can never paint and
 * building it would only burn allocation on every list recomposition.
 */
internal const val MARKDOWN_PREVIEW_MAX_LENGTH = 200

/** [markdownDocumentToPreviewAnnotatedString] with the chat-row code-chip style. */
@Composable
internal fun rememberMarkdownPreviewText(
    document: MarkdownDocumentFfi,
    mentionDisplayName: ((String) -> String?)? = null,
): AnnotatedString {
    val contentColor = LocalContentColor.current
    // Same name-resolution pattern as rememberMarkdownInlineText: resolve in
    // composition (subscribing to the profile revision) and key the cache on
    // the result so a late-arriving profile re-flattens the row.
    val mentionBech32s = remember(document) { markdownDocumentMentionBech32s(document) }
    val mentionNames =
        if (mentionDisplayName == null) {
            emptyMap()
        } else {
            mentionBech32s.associateWith(mentionDisplayName)
        }
    return remember(document, contentColor, mentionNames) {
        markdownDocumentToPreviewAnnotatedString(
            document = document,
            codeStyle =
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = contentColor.copy(alpha = 0.08f),
                ),
            mentionDisplayName = mentionNames::get,
        )
    }
}

/**
 * Pure document → single-line [AnnotatedString] flattening for the chat-list
 * preview row (kept free of composition so it's unit-testable, like
 * [markdownInlinesToAnnotatedString]).
 *
 * Rules:
 * - Blocks are walked in order; each block's text contribution is joined to
 *   the previous one with a single space. Structure-only blocks (thematic
 *   breaks) contribute nothing.
 * - Paragraphs, headings, quote bodies, list items, and table cells flatten
 *   to their inline runs. Code and math blocks contribute their content in
 *   [codeStyle] with internal whitespace collapsed to single spaces.
 * - Inline styling survives: bold, italic, strikethrough, and the inline-code
 *   chip. Line breaks become spaces — the preview is one line by contract.
 * - Links, autolinks, and images render their visible text with NO
 *   [LinkAnnotation] and no link styling: the row's only tap target is the
 *   chat itself, so nothing in the preview may look or act tappable.
 * - Nostr mentions/URIs show the same visible text as the bubble (resolved
 *   display name or shortened bech32) but, like links, stay annotation-free.
 * - The result is capped at [maxLength]; the walk stops early once the budget
 *   is spent so a huge message never builds a giant string for a one-line row.
 */
internal fun markdownDocumentToPreviewAnnotatedString(
    document: MarkdownDocumentFfi,
    codeStyle: SpanStyle,
    maxLength: Int = MARKDOWN_PREVIEW_MAX_LENGTH,
    mentionDisplayName: ((String) -> String?)? = null,
): AnnotatedString {
    val flattened =
        buildAnnotatedString {
            for (block in markdownVisibleSiblings(document.blocks)) {
                if (length >= maxLength) break
                appendPreviewBlock(block, codeStyle, maxLength, mentionDisplayName, depth = 0)
            }
        }
    return if (flattened.length > maxLength) flattened.previewSubSequence(maxLength) else flattened
}

private fun AnnotatedString.Builder.appendPreviewBlock(
    block: MarkdownBlockFfi,
    codeStyle: SpanStyle,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
    depth: Int,
) {
    // Budget check inside the recursion too: the top-level loop only guards
    // between siblings, so a deep quote/list subtree would otherwise keep
    // flattening long after the row's budget is spent.
    if (length >= maxLength) return
    // Structural depth cap: a deeply-nested subtree with NO text content never
    // spends the length budget, so the budget alone can't bound the recursion
    // — a peer could overflow the stack while building a one-line preview. See #156.
    if (markdownDepthExceeded(depth)) return
    when (block) {
        is MarkdownBlockFfi.Paragraph -> appendPreviewInlineSegment(block.inlines, codeStyle, maxLength, mentionDisplayName)
        is MarkdownBlockFfi.Heading -> appendPreviewInlineSegment(block.inlines, codeStyle, maxLength, mentionDisplayName)
        MarkdownBlockFfi.ThematicBreak -> Unit
        is MarkdownBlockFfi.CodeBlock -> appendPreviewCodeContent(block.content, codeStyle, maxLength)
        is MarkdownBlockFfi.MathBlock -> appendPreviewCodeContent(block.content, codeStyle, maxLength)
        is MarkdownBlockFfi.BlockQuote ->
            markdownVisibleSiblings(block.blocks).forEach {
                appendPreviewBlock(it, codeStyle, maxLength, mentionDisplayName, depth + 1)
            }
        is MarkdownBlockFfi.ListBlock ->
            markdownVisibleSiblings(block.items).forEach { item ->
                markdownVisibleSiblings(item.blocks).forEach {
                    appendPreviewBlock(it, codeStyle, maxLength, mentionDisplayName, depth + 1)
                }
            }
        is MarkdownBlockFfi.Table -> {
            val visibleTable = markdownVisibleTable(block.header, block.rows)
            visibleTable.header.cells.forEach { cell ->
                if (length >= maxLength) return
                appendPreviewInlineSegment(cell.inlines, codeStyle, maxLength, mentionDisplayName)
            }
            visibleTable.rows.forEach { row ->
                if (length >= maxLength) return
                row.cells.forEach { cell ->
                    if (length >= maxLength) return
                    appendPreviewInlineSegment(cell.inlines, codeStyle, maxLength, mentionDisplayName)
                }
            }
        }
    }
}

private val previewWhitespaceRun = Regex("\\s+")

private fun String.previewTake(maxLength: Int): String {
    val end = previewSafeEnd(maxLength)
    return if (end == length) this else substring(0, end)
}

private fun AnnotatedString.previewSubSequence(maxLength: Int): AnnotatedString {
    val end = text.previewSafeEnd(maxLength)
    return subSequence(0, end)
}

private fun String.previewSafeEnd(maxLength: Int): Int {
    val end = maxLength.coerceIn(0, length)
    return if (end > 0 && end < length && Character.isHighSurrogate(this[end - 1])) {
        end - 1
    } else {
        end
    }
}

private fun AnnotatedString.Builder.appendPreviewCodeContent(
    content: String,
    codeStyle: SpanStyle,
    maxLength: Int,
) {
    // Bound the work BEFORE the whitespace collapse: a megabyte code block
    // must not be regex-processed for a one-line row. The window is generous
    // because collapsing only shrinks text; a pathological mostly-whitespace
    // prefix just yields a shorter preview, which the row can afford.
    // Bound the RAW content BEFORE sanitizing, so stripUnsafe never scans a
    // peer-crafted megabyte block in full for a one-line row. Sanitizing only
    // shrinks, so the pre-clip window stays a safe upper bound (#1031 review).
    val bounded = markdownSafeDisplayText(content.previewTake(maxLength * 8), Int.MAX_VALUE)
    // A code block is a multi-line region; the preview is one line. Collapse
    // every whitespace run (incl. newlines and indentation) to a single space
    // so `fun main() {\n  hi()\n}` reads as `fun main() { hi() }`.
    val singleLine = bounded.trim().replace(previewWhitespaceRun, " ")
    appendPreviewSegment(
        buildAnnotatedString { withStyle(codeStyle) { append(singleLine) } },
        maxLength,
    )
}

private fun AnnotatedString.Builder.appendPreviewInlineSegment(
    inlines: List<MarkdownInlineFfi>,
    codeStyle: SpanStyle,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
) {
    if (length >= maxLength) return
    appendPreviewSegment(
        buildAnnotatedString { appendPreviewInlines(inlines, codeStyle, maxLength, mentionDisplayName, depth = 0) },
        maxLength,
    )
}

/**
 * Joins a leaf segment to the builder with the single-space block separator,
 * spending at most the remaining [maxLength] budget. The segment is
 * materialized first so an empty contribution (blank paragraph, empty table
 * cell) commits neither text nor a stray separator; a segment that overflows
 * the budget is cut at the boundary instead of being appended whole.
 */
private fun AnnotatedString.Builder.appendPreviewSegment(
    segment: AnnotatedString,
    maxLength: Int,
) {
    if (segment.isEmpty()) return
    val separator = if (length > 0) 1 else 0
    val remaining = maxLength - length - separator
    if (remaining <= 0) return
    val chunk = if (segment.length > remaining) segment.previewSubSequence(remaining) else segment
    if (chunk.isEmpty()) return
    if (separator == 1) append(' ')
    append(chunk)
}

private fun AnnotatedString.Builder.appendPreviewInlines(
    inlines: List<MarkdownInlineFfi>,
    codeStyle: SpanStyle,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
    depth: Int,
) {
    // Structural depth cap as well as the budget: a deeply-nested EMPTY inline
    // tree (e.g. emphasis nested thousands deep with no text) never spends the
    // length budget, so the budget alone can't bound this recursion. See #156.
    if (markdownInlineDepthExceeded(depth)) return
    for (inline in markdownVisibleSiblings(inlines)) {
        // This builds a segment (own builder, length starts at 0), so the
        // whole-document budget bounds each segment: stop walking once spent
        // and cap the unbounded leaf appends (text/code/math/autolink) so one
        // giant run can't blow past it either.
        if (length >= maxLength) return
        when (inline) {
            is MarkdownInlineFfi.Text -> append(markdownSafeDisplayText(inline.content.previewTake(maxLength - length), Int.MAX_VALUE))
            // One-line preview: the author's line breaks flatten to spaces
            // (unlike the bubble renderer, which preserves them).
            MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> append(' ')
            is MarkdownInlineFfi.Code ->
                withStyle(codeStyle) {
                    append(markdownSafeDisplayText(inline.content.previewTake((maxLength - length).coerceAtLeast(0)), Int.MAX_VALUE))
                }
            is MarkdownInlineFfi.Emph ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendPreviewInlines(inline.children, codeStyle, maxLength, mentionDisplayName, depth + 1)
                }
            is MarkdownInlineFfi.Strong ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendPreviewInlines(inline.children, codeStyle, maxLength, mentionDisplayName, depth + 1)
                }
            is MarkdownInlineFfi.Strikethrough ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendPreviewInlines(inline.children, codeStyle, maxLength, mentionDisplayName, depth + 1)
                }
            // Visible text only — no annotation, no link styling. A label-less
            // link still shows its destination so the preview isn't blank.
            is MarkdownInlineFfi.Link ->
                appendPreviewInlines(
                    inline.children.ifEmpty { listOf(MarkdownInlineFfi.Text(inline.dest.trim())) },
                    codeStyle,
                    maxLength,
                    mentionDisplayName,
                    depth + 1,
                )
            is MarkdownInlineFfi.Image ->
                appendPreviewInlines(
                    inline.alt.ifEmpty { listOf(MarkdownInlineFfi.Text(inline.dest.trim())) },
                    codeStyle,
                    maxLength,
                    mentionDisplayName,
                    depth + 1,
                )
            is MarkdownInlineFfi.Autolink -> append(markdownSafeDisplayText(inline.url.previewTake(maxLength - length), Int.MAX_VALUE))
            is MarkdownInlineFfi.Math ->
                withStyle(codeStyle) {
                    append(markdownSafeDisplayText(inline.content.previewTake((maxLength - length).coerceAtLeast(0)), Int.MAX_VALUE))
                }
            // Same visible text as the bubble (name or shortened bech32) but
            // inert: the row's only tap target is the chat itself.
            is MarkdownInlineFfi.NostrMention -> {
                val name = mentionDisplayName?.invoke(inline.entity.bech32)
                if (name != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("@$name") }
                } else {
                    withStyle(codeStyle) {
                        append('@')
                        append(shortenedBech32(inline.entity.bech32))
                    }
                }
            }
            is MarkdownInlineFfi.NostrUri ->
                withStyle(codeStyle) { append(shortenedBech32(inline.entity.bech32)) }
        }
    }
}

internal fun markdownListMarker(
    kind: MarkdownListKindFfi,
    index: Int,
): String =
    when (kind) {
        is MarkdownListKindFfi.Bullet -> "•"
        // Compute in Long: UInt addition wraps silently, and `start` is an
        // FFI-supplied value we don't control.
        is MarkdownListKindFfi.Ordered -> "${kind.start.toLong() + index}${kind.delimiter}"
    }

/**
 * Schemes handed to `ACTION_VIEW`; everything else stays inert text. `tel:` and
 * custom app schemes are excluded so an untrusted peer link can't dial or
 * deep-link into another app; `nostr:` routes in-app via
 * [NOSTR_PROFILE_LINK_TAG_PREFIX], never out.
 */
internal data class ParsedOpenableMarkdownLink(
    val destination: String,
    val effectiveAuthority: String?,
)

/** Parses an allowed link and canonicalizes Unicode HTTP hosts to their ASCII form. */
internal fun parsedOpenableMarkdownLink(dest: String): ParsedOpenableMarkdownLink? {
    val trimmed = dest.trim()
    if (trimmed.isEmpty() || ProfileSanitizer.stripUnsafe(trimmed) != trimmed) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "http", "https" -> parsedHttpMarkdownLink(uri)
        "mailto" ->
            uri.rawSchemeSpecificPart
                ?.takeIf { it.isNotBlank() }
                ?.let { ParsedOpenableMarkdownLink(trimmed, null) }
        else -> null
    }
}

internal fun isOpenableMarkdownLink(dest: String): Boolean = parsedOpenableMarkdownLink(dest) != null

/** Security-relevant authority shown separately from the truncated full URL. */
internal fun markdownLinkEffectiveAuthority(dest: String): String? = parsedOpenableMarkdownLink(dest)?.effectiveAuthority

private fun parsedHttpMarkdownLink(uri: URI): ParsedOpenableMarkdownLink? {
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    val rawAuthority = uri.rawAuthority ?: return null
    if (rawAuthority.isBlank() || uri.rawUserInfo != null || '@' in rawAuthority) return null
    val canonicalAuthority = canonicalHttpAuthority(rawAuthority, uri) ?: return null
    val rawSchemeSpecificPart = uri.rawSchemeSpecificPart ?: return null
    val authorityPrefix = "//$rawAuthority"
    if (!rawSchemeSpecificPart.startsWith(authorityPrefix)) return null
    val suffix = rawSchemeSpecificPart.removePrefix(authorityPrefix)
    val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
    val destination = "$scheme://$canonicalAuthority$suffix$fragment"
    val canonicalUri = runCatching { URI(destination) }.getOrNull() ?: return null
    if (canonicalUri.rawUserInfo != null || canonicalUri.host.isNullOrBlank()) return null
    return ParsedOpenableMarkdownLink(
        destination = destination,
        effectiveAuthority = "$scheme://$canonicalAuthority",
    )
}

private fun canonicalHttpAuthority(
    rawAuthority: String,
    uri: URI,
): String? {
    if (rawAuthority.startsWith('[')) {
        val closingBracket = rawAuthority.indexOf(']')
        if (closingBracket <= 1 || uri.host.isNullOrBlank()) return null
        val host = rawAuthority.substring(0, closingBracket + 1).lowercase(Locale.ROOT)
        val port = canonicalPortSuffix(rawAuthority.substring(closingBracket + 1)) ?: return null
        return host + port
    }
    if (rawAuthority.count { it == ':' } > 1) return null
    val portSeparator = rawAuthority.lastIndexOf(':')
    val rawHost = if (portSeparator >= 0) rawAuthority.substring(0, portSeparator) else rawAuthority
    val rawPort = if (portSeparator >= 0) rawAuthority.substring(portSeparator) else ""
    val host =
        runCatching { IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES) }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return null
    val port = canonicalPortSuffix(rawPort) ?: return null
    return host + port
}

private fun canonicalPortSuffix(rawPort: String): String? {
    if (rawPort.isEmpty()) return ""
    if (!rawPort.startsWith(':')) return null
    val port = rawPort.drop(1).toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
    return ":$port"
}

/**
 * Same fire-and-catch pattern as `openAttachmentExternally`: no
 * `resolveActivity` pre-flight (package visibility makes it lie), just catch
 * `ActivityNotFoundException` as the authoritative "no handler" signal and
 * swallow it — a dead tap beats a crash. One ACTION_VIEW path serves every
 * allowed external scheme.
 */
private fun openMarkdownLink(
    context: android.content.Context,
    url: String,
) {
    // Re-parse at launch so ACTION_VIEW receives the same canonical target the
    // annotation and confirmation UI were built from.
    val parsedLink = parsedOpenableMarkdownLink(url) ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(parsedLink.destination))
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        // No handler for this scheme on the device — nothing sane to do.
    }
}
