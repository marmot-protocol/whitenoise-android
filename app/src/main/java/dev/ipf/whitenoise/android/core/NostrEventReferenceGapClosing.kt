package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownInlineFfi

/**
 * [this] with every repeat of a represented event removed and the spacing around each one closed up, so the prose
 * reads as if the reference had never been typed: brackets or quotes left empty go, one space stays between words and
 * a line left empty loses its break. Without a repeat the list is returned as it is.
 */
internal fun List<MarkdownInlineFfi>.withoutRepeatsOf(represented: Set<String>): List<MarkdownInlineFfi> {
    if (none { it.repeatsRepresentedEvent(represented) }) return this
    val kept = ArrayList<MarkdownInlineFfi>(size)
    var afterRepeat = false
    var gap = false
    for (inline in this) {
        when {
            inline.repeatsRepresentedEvent(represented) -> {
                gap = kept.trimTrailingSpace() || gap
                afterRepeat = true
            }
            !afterRepeat -> kept += inline
            inline.isSpaceOnly() -> gap = true
            else -> {
                kept.appendAfterRepeat(inline, gap)
                afterRepeat = false
                gap = false
            }
        }
    }
    return kept
}

/** Trims the spaces that end the last kept text, returning whether there were any. */
private fun MutableList<MarkdownInlineFfi>.trimTrailingSpace(): Boolean {
    val previous = lastOrNull() as? MarkdownInlineFfi.Text ?: return false
    val trimmed = previous.content.trimEnd(' ', '\t')
    this[lastIndex] = MarkdownInlineFfi.Text(trimmed)
    return trimmed.length != previous.content.length
}

/** Appends the inline that follows a removed repeat, dropping a break that would leave its line empty. */
private fun MutableList<MarkdownInlineFfi>.appendAfterRepeat(
    inline: MarkdownInlineFfi,
    gap: Boolean,
) {
    when {
        inline.isSoftOrHardBreak() -> if (!isAtLineStart()) add(inline)
        inline is MarkdownInlineFfi.Text -> add(MarkdownInlineFfi.Text(closeUp(inline.content, gap)))
        else -> {
            if (gap && !isAtLineStart()) add(MarkdownInlineFfi.Text(" "))
            add(inline)
        }
    }
}

/**
 * [next] with the gap before it closed up. A bracket or quote opened just before the removed repeat and closed just
 * after it is removed from both sides, and one space is kept when words continue on the same line.
 */
private fun MutableList<MarkdownInlineFfi>.closeUp(
    next: String,
    gap: Boolean,
): String {
    var rest = next.trimStart(' ', '\t')
    var hadSpace = gap || rest.length != next.length
    val previousIndex = indexOfLast { it !is MarkdownInlineFfi.Text || it.content.isNotEmpty() }
    val previous = getOrNull(previousIndex) as? MarkdownInlineFfi.Text
    val opener = previous?.content?.lastOrNull()
    if (previous != null && opener != null && rest.firstOrNull() == BRACKET_PAIRS[opener]) {
        val before = previous.content.dropLast(1)
        val trimmedBefore = before.trimEnd(' ', '\t')
        this[previousIndex] = MarkdownInlineFfi.Text(trimmedBefore)
        val after = rest.drop(1)
        rest = after.trimStart(' ', '\t')
        hadSpace = trimmedBefore.length != before.length || rest.length != after.length
    }
    val continues = rest.firstOrNull()?.let { it !in CLOSING_PUNCTUATION } == true
    return if (hadSpace && continues && !isAtLineStart()) " $rest" else rest
}

/** Whether nothing but empty text has been kept since the start or the last line break. */
private fun List<MarkdownInlineFfi>.isAtLineStart(): Boolean {
    val previous = lastOrNull { it !is MarkdownInlineFfi.Text || it.content.isNotEmpty() }
    return previous == null || previous.isSoftOrHardBreak()
}

/** Whether [this] is text of spaces or tabs alone, which only separates a repeat from what follows. */
private fun MarkdownInlineFfi.isSpaceOnly(): Boolean {
    if (this !is MarkdownInlineFfi.Text) return false
    return content.isNotEmpty() && content.all { it == ' ' || it == '\t' }
}

// Openers whose closer may sit right after a removed repeat, so the empty pair goes with it.
private val BRACKET_PAIRS =
    mapOf('(' to ')', '[' to ']', '{' to '}', '«' to '»', '“' to '”', '‘' to '’', '"' to '"', '\'' to '\'')

// Punctuation that attaches to the word before it, so no space is kept ahead of it.
private const val CLOSING_PUNCTUATION = ".,;:!?)]}»”’…"
