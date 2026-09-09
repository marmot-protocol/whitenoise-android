package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownInlineFfi

/** Walks a bounded inline window, stopping as soon as the preview is full. */
internal fun MarkdownPreviewBuilder.appendPreviewInlines(
    inlines: List<MarkdownInlineFfi>,
    codeStyle: MarkdownPreviewStyle,
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
        appendPreviewInline(inline, codeStyle, maxLength, mentionDisplayName, depth)
    }
}

/** Appends one visible inline while preserving the shared depth and character budgets. */
private fun MarkdownPreviewBuilder.appendPreviewInline(
    inline: MarkdownInlineFfi,
    codeStyle: MarkdownPreviewStyle,
    maxLength: Int,
    mentionDisplayName: ((String) -> String?)?,
    depth: Int,
) {
    when (inline) {
        is MarkdownInlineFfi.Text -> appendPreviewLeaf(inline.content, maxLength)
        // One-line preview: the author's line breaks flatten to spaces
        // (unlike the bubble renderer, which preserves them).
        MarkdownInlineFfi.SoftBreak, MarkdownInlineFfi.HardBreak -> append(' ')
        is MarkdownInlineFfi.Code ->
            withStyle(codeStyle) {
                appendPreviewLeaf(inline.content, maxLength)
            }
        is MarkdownInlineFfi.Emph ->
            withStyle(MarkdownPreviewStyle.Italic) {
                appendPreviewInlines(inline.children, codeStyle, maxLength, mentionDisplayName, depth + 1)
            }
        is MarkdownInlineFfi.Strong ->
            withStyle(MarkdownPreviewStyle.Bold) {
                appendPreviewInlines(inline.children, codeStyle, maxLength, mentionDisplayName, depth + 1)
            }
        is MarkdownInlineFfi.Strikethrough ->
            withStyle(MarkdownPreviewStyle.Strike) {
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
        is MarkdownInlineFfi.Autolink -> appendPreviewLeaf(inline.url, maxLength)
        is MarkdownInlineFfi.Math ->
            withStyle(codeStyle) {
                appendPreviewLeaf(inline.content, maxLength)
            }
        // Same visible text as the bubble (name or shortened bech32) but
        // inert: the row's only tap target is the chat itself.
        is MarkdownInlineFfi.NostrMention -> appendPreviewMention(inline.entity.bech32, codeStyle, mentionDisplayName)
        is MarkdownInlineFfi.NostrUri ->
            withStyle(codeStyle) { append(shortenedBech32(inline.entity.bech32)) }
    }
}

/** Sanitizes only the raw prefix which can still contribute to this preview. */
private fun MarkdownPreviewBuilder.appendPreviewLeaf(
    content: String,
    maxLength: Int,
) {
    append(markdownSafeDisplayText(content.previewTake((maxLength - length).coerceAtLeast(0)), Int.MAX_VALUE))
}

/** Keeps named and unresolved profile mentions identical in plain and styled previews. */
private fun MarkdownPreviewBuilder.appendPreviewMention(
    bech32: String,
    codeStyle: MarkdownPreviewStyle,
    mentionDisplayName: ((String) -> String?)?,
) {
    val name = mentionDisplayName?.invoke(bech32)
    if (name != null) {
        withStyle(MarkdownPreviewStyle.Bold) { append("@$name") }
    } else {
        withStyle(codeStyle) {
            append('@')
            append(shortenedBech32(bech32))
        }
    }
}
