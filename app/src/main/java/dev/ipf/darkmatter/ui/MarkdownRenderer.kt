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
import androidx.compose.material3.Typography
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
 * Nostr entities are first-class: a mention renders as "@DisplayName" (bold)
 * when [mentionDisplayName] resolves one, else as its shortened bech32 in the
 * code style; npub/nprofile entities route taps to [onNostrProfileTap] (an
 * in-app profile presentation — identity taps are never handed to external
 * apps via ACTION_VIEW). note/nevent/naddr/nrelay stay styled but inert.
 */
@Composable
internal fun MarkdownMessageBody(
    document: MarkdownDocumentFfi,
    modifier: Modifier = Modifier,
    mentionDisplayName: ((String) -> String?)? = null,
    onNostrProfileTap: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    // One listener for every link in the document; the tapped destination
    // rides in on the annotation itself. URL annotations go through the
    // scheme gate to ACTION_VIEW; Clickable annotations carry a nostr
    // profile entity and stay in-app.
    val linkListener =
        remember(context, onNostrProfileTap) {
            LinkInteractionListener { annotation ->
                when (annotation) {
                    is LinkAnnotation.Url -> openMarkdownLink(context, annotation.url)
                    is LinkAnnotation.Clickable ->
                        annotation.tag
                            .takeIf { it.startsWith(NOSTR_PROFILE_LINK_TAG_PREFIX) }
                            ?.removePrefix(NOSTR_PROFILE_LINK_TAG_PREFIX)
                            ?.let { bech32 -> onNostrProfileTap?.invoke(bech32) }
                    else -> Unit
                }
            }
        }
    val bodyContext =
        remember(linkListener, mentionDisplayName) {
            MarkdownBodyContext(linkListener, mentionDisplayName)
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        document.blocks.forEach { block ->
            MarkdownBlockView(block, bodyContext)
        }
    }
}

/** Per-document inputs threaded through every block view. */
private data class MarkdownBodyContext(
    val linkListener: LinkInteractionListener,
    val mentionDisplayName: ((String) -> String?)?,
)

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlockFfi,
    ctx: MarkdownBodyContext,
) {
    when (block) {
        is MarkdownBlockFfi.Paragraph ->
            Text(
                rememberMarkdownInlineText(block.inlines, ctx),
                style = MaterialTheme.typography.bodyLarge,
            )
        is MarkdownBlockFfi.Heading ->
            Text(
                rememberMarkdownInlineText(block.inlines, ctx),
                style = markdownHeadingTextStyle(block.level.toInt(), MaterialTheme.typography),
            )
        MarkdownBlockFfi.ThematicBreak ->
            HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.25f))
        is MarkdownBlockFfi.CodeBlock -> MarkdownCodeBlockView(block.content)
        is MarkdownBlockFfi.BlockQuote -> MarkdownBlockQuoteView(block.blocks, ctx)
        is MarkdownBlockFfi.List -> MarkdownListView(block, ctx)
        is MarkdownBlockFfi.Table -> MarkdownTableView(block, ctx)
        // No math typesetting in v1 — show the raw TeX in the code treatment
        // so it at least reads as "source", not as broken prose.
        is MarkdownBlockFfi.MathBlock -> MarkdownCodeBlockView(block.content)
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
    ctx: MarkdownBodyContext,
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { MarkdownBlockView(it, ctx) }
        }
    }
}

@Composable
private fun MarkdownListView(
    block: MarkdownBlockFfi.List,
    ctx: MarkdownBodyContext,
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
                    item.blocks.forEach { MarkdownBlockView(it, ctx) }
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
    ctx: MarkdownBodyContext,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MarkdownTableRowView(block.header, block.alignments, header = true, ctx)
        HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.25f))
        block.rows.forEach { row ->
            MarkdownTableRowView(row, block.alignments, header = false, ctx)
        }
    }
}

@Composable
private fun MarkdownTableRowView(
    cells: List<MarkdownTableCellFfi>,
    alignments: List<MarkdownAlignmentFfi>,
    header: Boolean,
    ctx: MarkdownBodyContext,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.forEachIndexed { index, cell ->
            Text(
                rememberMarkdownInlineText(cell.inlines, ctx),
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
    ctx: MarkdownBodyContext,
): AnnotatedString {
    val contentColor = LocalContentColor.current
    // Resolve mention display names during composition, not inside remember's
    // calculation: the resolver reads Compose state (the profile revision), so
    // the read must both subscribe this scope AND change a remember key when a
    // profile arrives — otherwise the cached string would survive the
    // recomposition and the mention would never upgrade from bech32 to name.
    val mentionNames = resolveMentionNames(inlines, ctx.mentionDisplayName)
    // Links must derive from the content color like every other accent:
    // colorScheme.primary disappears on the outgoing bubble, whose container
    // IS primary. Underline alone carries the affordance on both surfaces.
    return remember(inlines, contentColor, ctx, mentionNames) {
        markdownInlinesToAnnotatedString(
            inlines = inlines,
            codeStyle =
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = contentColor.copy(alpha = 0.08f),
                ),
            linkStyle =
                SpanStyle(
                    color = contentColor,
                    textDecoration = TextDecoration.Underline,
                ),
            linkListener = ctx.linkListener,
            mentionDisplayName = mentionNames::get,
        )
    }
}

/** Mention bech32 → resolved display name (or null) for one inline tree. */
private fun resolveMentionNames(
    inlines: List<MarkdownInlineFfi>,
    resolve: ((String) -> String?)?,
): Map<String, String?> {
    if (resolve == null) return emptyMap()
    val bech32s = mutableSetOf<String>()
    collectMentionBech32s(inlines, bech32s)
    return bech32s.associateWith(resolve)
}

private fun collectMentionBech32s(
    inlines: List<MarkdownInlineFfi>,
    out: MutableSet<String>,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInlineFfi.NostrMention -> out += inline.entity.bech32
            is MarkdownInlineFfi.Emph -> collectMentionBech32s(inline.children, out)
            is MarkdownInlineFfi.Strong -> collectMentionBech32s(inline.children, out)
            is MarkdownInlineFfi.Strikethrough -> collectMentionBech32s(inline.children, out)
            is MarkdownInlineFfi.Link -> collectMentionBech32s(inline.children, out)
            is MarkdownInlineFfi.Image -> collectMentionBech32s(inline.alt, out)
            else -> Unit
        }
    }
}

private fun collectBlockMentionBech32s(
    blocks: List<MarkdownBlockFfi>,
    out: MutableSet<String>,
) {
    blocks.forEach { block ->
        when (block) {
            is MarkdownBlockFfi.Paragraph -> collectMentionBech32s(block.inlines, out)
            is MarkdownBlockFfi.Heading -> collectMentionBech32s(block.inlines, out)
            is MarkdownBlockFfi.BlockQuote -> collectBlockMentionBech32s(block.blocks, out)
            is MarkdownBlockFfi.List ->
                block.items.forEach { collectBlockMentionBech32s(it.blocks, out) }
            is MarkdownBlockFfi.Table -> {
                block.header.forEach { collectMentionBech32s(it.inlines, out) }
                block.rows.forEach { row -> row.forEach { collectMentionBech32s(it.inlines, out) } }
            }
            else -> Unit
        }
    }
}

/**
 * [LinkAnnotation.Clickable] tag prefix for nostr profile entities
 * (npub/nprofile). These deliberately do NOT become [LinkAnnotation.Url]s:
 * an identity tap must stay in-app (profile sheet), never fan out to whatever
 * external app claims the nostr: scheme.
 */
internal const val NOSTR_PROFILE_LINK_TAG_PREFIX = "nostr-profile:"

/**
 * Pure inline-tree → [AnnotatedString] mapping (kept free of composition so
 * it's unit-testable). Only allowlisted destinations (see
 * [isOpenableMarkdownLink]) become tappable [LinkAnnotation.Url]s; anything
 * else (javascript:, data:, file:, …) renders its visible text with no
 * annotation at all, so there is nothing to tap and nothing to launch.
 * Nostr mentions resolve through [mentionDisplayName] (bold "@Name") or fall
 * back to their shortened bech32 in [codeStyle].
 */
internal fun markdownInlinesToAnnotatedString(
    inlines: List<MarkdownInlineFfi>,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    linkListener: LinkInteractionListener? = null,
    mentionDisplayName: ((String) -> String?)? = null,
): AnnotatedString =
    buildAnnotatedString {
        appendMarkdownInlines(
            inlines,
            MarkdownInlineRenderContext(codeStyle, linkStyle, linkListener, mentionDisplayName),
        )
    }

/** Immutable bundle threaded through the recursive inline walk. */
private class MarkdownInlineRenderContext(
    val codeStyle: SpanStyle,
    val linkStyle: SpanStyle,
    val linkListener: LinkInteractionListener?,
    val mentionDisplayName: ((String) -> String?)?,
)

private fun AnnotatedString.Builder.appendMarkdownInlines(
    inlines: List<MarkdownInlineFfi>,
    ctx: MarkdownInlineRenderContext,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInlineFfi.Text -> append(inline.content)
            // Chat keeps the author's line breaks: a soft break renders as a
            // newline (not the CommonMark collapse-to-space) to match how the
            // plaintext fallback has always displayed.
            MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> append('\n')
            is MarkdownInlineFfi.Code -> withStyle(ctx.codeStyle) { append(inline.content) }
            is MarkdownInlineFfi.Emph ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendMarkdownInlines(inline.children, ctx)
                }
            is MarkdownInlineFfi.Strong ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendMarkdownInlines(inline.children, ctx)
                }
            is MarkdownInlineFfi.Strikethrough ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendMarkdownInlines(inline.children, ctx)
                }
            is MarkdownInlineFfi.Link ->
                appendMarkdownLink(inline.dest, inline.children, ctx)
            // No inline image fetches (they'd bypass the encrypted-media
            // pipeline): the alt text stands in, tappable through to the
            // image URL when its scheme is allowlisted.
            is MarkdownInlineFfi.Image ->
                appendMarkdownLink(inline.dest, inline.alt, ctx)
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
                if (isOpenableMarkdownLink(dest)) {
                    withLink(LinkAnnotation.Url(dest, TextLinkStyles(style = ctx.linkStyle), ctx.linkListener)) {
                        append(inline.url)
                    }
                } else {
                    // Non-allowlisted URIs stay visible but inert.
                    append(inline.url)
                }
            }
            is MarkdownInlineFfi.Math -> withStyle(ctx.codeStyle) { append(inline.content) }
            is MarkdownInlineFfi.NostrMention -> appendNostrEntity(inline.entity, mention = true, ctx)
            is MarkdownInlineFfi.NostrUri -> appendNostrEntity(inline.entity, mention = false, ctx)
        }
    }
}

/**
 * Mention → "@DisplayName" (bold) when resolvable, else "@" + shortened
 * bech32 in the code style; a plain nostr: URI shows the shortened bech32
 * without the "@" and never resolves a name. npub/nprofile entities carry a
 * [LinkAnnotation.Clickable] routed (via the shared listener) to the in-app
 * profile sheet; the other HRPs (note/nevent/naddr/nrelay) have no in-app
 * destination yet, so they stay inert.
 */
private fun AnnotatedString.Builder.appendNostrEntity(
    entity: MarkdownNostrEntityFfi,
    mention: Boolean,
    ctx: MarkdownInlineRenderContext,
) {
    val name = if (mention) ctx.mentionDisplayName?.invoke(entity.bech32) else null
    // The annotated run borrows the link color (LocalContentColor in the
    // bubble): a Clickable region is painted with ITS OWN TextLinkStyles —
    // when those are null, Material's Text falls back to the theme's default
    // link color (primary), which is invisible on the outgoing
    // primary-container bubble. Same color policy as linkStyle itself.
    val style =
        if (name != null) {
            SpanStyle(color = ctx.linkStyle.color, fontWeight = FontWeight.Bold)
        } else {
            ctx.codeStyle.copy(color = ctx.linkStyle.color)
        }
    val visible = if (name != null) "@$name" else (if (mention) "@" else "") + shortenedBech32(entity.bech32)
    val opensProfile =
        entity.hrp == MarkdownNostrHrpFfi.NPUB || entity.hrp == MarkdownNostrHrpFfi.NPROFILE
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
        withStyle(if (name != null) SpanStyle(fontWeight = FontWeight.Bold) else ctx.codeStyle) {
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

private fun AnnotatedString.Builder.appendMarkdownLink(
    dest: String,
    children: List<MarkdownInlineFfi>,
    ctx: MarkdownInlineRenderContext,
) {
    // Normalize once at the boundary so the openability gate, the stored
    // annotation, and the eventual ACTION_VIEW all agree on the same string.
    // A whitespace-padded URL would otherwise pass the (trimming) gate but
    // lose its scheme in Uri.parse(" https://…").
    val normalizedDest = dest.trim()
    // A label-less link (`[](url)` or an image with empty alt) would otherwise
    // produce a zero-length, untappable annotation — show the URL itself.
    val visible = children.ifEmpty { listOf(MarkdownInlineFfi.Text(normalizedDest)) }
    if (isOpenableMarkdownLink(normalizedDest)) {
        withLink(LinkAnnotation.Url(normalizedDest, TextLinkStyles(style = ctx.linkStyle), ctx.linkListener)) {
            appendMarkdownInlines(visible, ctx)
        }
    } else {
        appendMarkdownInlines(visible, ctx)
    }
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
    val mentionNames =
        if (mentionDisplayName == null) {
            emptyMap()
        } else {
            val bech32s = mutableSetOf<String>()
            collectBlockMentionBech32s(document.blocks, bech32s)
            bech32s.associateWith(mentionDisplayName)
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
            for (block in document.blocks) {
                if (length >= maxLength) break
                appendPreviewBlock(block, codeStyle, maxLength, mentionDisplayName)
            }
        }
    return if (flattened.length > maxLength) flattened.subSequence(0, maxLength) else flattened
}

private fun AnnotatedString.Builder.appendPreviewBlock(
    block: MarkdownBlockFfi,
    codeStyle: SpanStyle,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
) {
    // Budget check inside the recursion too: the top-level loop only guards
    // between siblings, so a deep quote/list subtree would otherwise keep
    // flattening long after the row's budget is spent.
    if (length >= maxLength) return
    when (block) {
        is MarkdownBlockFfi.Paragraph -> appendPreviewInlineSegment(block.inlines, codeStyle, maxLength, mentionDisplayName)
        is MarkdownBlockFfi.Heading -> appendPreviewInlineSegment(block.inlines, codeStyle, maxLength, mentionDisplayName)
        MarkdownBlockFfi.ThematicBreak -> Unit
        is MarkdownBlockFfi.CodeBlock -> appendPreviewCodeContent(block.content, codeStyle, maxLength)
        is MarkdownBlockFfi.MathBlock -> appendPreviewCodeContent(block.content, codeStyle, maxLength)
        is MarkdownBlockFfi.BlockQuote ->
            block.blocks.forEach { appendPreviewBlock(it, codeStyle, maxLength, mentionDisplayName) }
        is MarkdownBlockFfi.List ->
            block.items.forEach { item ->
                item.blocks.forEach { appendPreviewBlock(it, codeStyle, maxLength, mentionDisplayName) }
            }
        is MarkdownBlockFfi.Table -> {
            block.header.forEach { cell -> appendPreviewInlineSegment(cell.inlines, codeStyle, maxLength, mentionDisplayName) }
            block.rows.forEach { row ->
                row.forEach { cell -> appendPreviewInlineSegment(cell.inlines, codeStyle, maxLength, mentionDisplayName) }
            }
        }
    }
}

private val previewWhitespaceRun = Regex("\\s+")

private fun AnnotatedString.Builder.appendPreviewCodeContent(
    content: String,
    codeStyle: SpanStyle,
    maxLength: Int,
) {
    // Bound the work BEFORE the whitespace collapse: a megabyte code block
    // must not be regex-processed for a one-line row. The window is generous
    // because collapsing only shrinks text; a pathological mostly-whitespace
    // prefix just yields a shorter preview, which the row can afford.
    val bounded = content.take(maxLength * 8)
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
    appendPreviewSegment(
        buildAnnotatedString { appendPreviewInlines(inlines, codeStyle, maxLength, mentionDisplayName) },
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
    if (separator == 1) append(' ')
    append(if (segment.length > remaining) segment.subSequence(0, remaining) else segment)
}

private fun AnnotatedString.Builder.appendPreviewInlines(
    inlines: List<MarkdownInlineFfi>,
    codeStyle: SpanStyle,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
) {
    for (inline in inlines) {
        // This builds a segment (own builder, length starts at 0), so the
        // whole-document budget bounds each segment: stop walking once spent
        // and cap the unbounded leaf appends (text/code/math/autolink) so one
        // giant run can't blow past it either.
        if (length >= maxLength) return
        when (inline) {
            is MarkdownInlineFfi.Text -> append(inline.content.take(maxLength - length))
            // One-line preview: the author's line breaks flatten to spaces
            // (unlike the bubble renderer, which preserves them).
            MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> append(' ')
            is MarkdownInlineFfi.Code ->
                withStyle(codeStyle) { append(inline.content.take((maxLength - length).coerceAtLeast(0))) }
            is MarkdownInlineFfi.Emph ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendPreviewInlines(inline.children, codeStyle, maxLength, mentionDisplayName)
                }
            is MarkdownInlineFfi.Strong ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendPreviewInlines(inline.children, codeStyle, maxLength, mentionDisplayName)
                }
            is MarkdownInlineFfi.Strikethrough ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendPreviewInlines(inline.children, codeStyle, maxLength, mentionDisplayName)
                }
            // Visible text only — no annotation, no link styling. A label-less
            // link still shows its destination so the preview isn't blank.
            is MarkdownInlineFfi.Link ->
                appendPreviewInlines(
                    inline.children.ifEmpty { listOf(MarkdownInlineFfi.Text(inline.dest.trim())) },
                    codeStyle,
                    maxLength,
                    mentionDisplayName,
                )
            is MarkdownInlineFfi.Image ->
                appendPreviewInlines(
                    inline.alt.ifEmpty { listOf(MarkdownInlineFfi.Text(inline.dest.trim())) },
                    codeStyle,
                    maxLength,
                    mentionDisplayName,
                )
            is MarkdownInlineFfi.Autolink -> append(inline.url.take(maxLength - length))
            is MarkdownInlineFfi.Math ->
                withStyle(codeStyle) { append(inline.content.take((maxLength - length).coerceAtLeast(0))) }
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
 * Explicit scheme allowlist — only these are handed to `ACTION_VIEW`;
 * everything else (javascript:, data:, file:, ftp:, intent:, anything
 * unknown) stays inert text. nostr: is also absent on purpose: profile
 * entities route in-app via [NOSTR_PROFILE_LINK_TAG_PREFIX], never to an
 * arbitrary external handler.
 */
private val openableMarkdownLinkSchemes =
    setOf("http", "https", "mailto", "tel", "whitenoise", "whitenoise-staging")

internal fun isOpenableMarkdownLink(dest: String): Boolean {
    val trimmed = dest.trim()
    val colon = trimmed.indexOf(':')
    if (colon <= 0) return false
    return trimmed.substring(0, colon).lowercase() in openableMarkdownLinkSchemes
}

/**
 * Same fire-and-catch pattern as `openAttachmentExternally`: no
 * `resolveActivity` pre-flight (package visibility makes it lie), just catch
 * `ActivityNotFoundException` as the authoritative "no handler" signal and
 * swallow it — a dead tap beats a crash. One ACTION_VIEW path serves every
 * allowed scheme (browser, mail, dialer, whitenoise deep links alike).
 */
private fun openMarkdownLink(
    context: android.content.Context,
    url: String,
) {
    // Annotations are built from trimmed destinations, but re-normalize here
    // so the launch-time re-check and Uri.parse always agree — a padded URL
    // must never pass the gate and then lose its scheme in Uri.parse.
    val normalized = url.trim()
    if (!isOpenableMarkdownLink(normalized)) return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(normalized))
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        // No handler for this scheme on the device — nothing sane to do.
    }
}
