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
    if (afterRepeat) kept.dropTrailingBreaks()
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
 * [next] with the gap before it closed up. A bracket or quote pair left empty around the removed repeat goes with it,
 * a separator the text before already ends with is not repeated, and one space is kept when words continue on the
 * same line.
 */
private fun MutableList<MarkdownInlineFfi>.closeUp(
    next: String,
    gap: Boolean,
): String {
    var rest = next.trimStart(' ', '\t')
    var hadSpace = gap || rest.length != next.length
    val previousIndex = indexOfLast { it !is MarkdownInlineFfi.Text || it.content.isNotEmpty() }
    val previous = getOrNull(previousIndex) as? MarkdownInlineFfi.Text
    if (previous != null && closesEmptyPair(previous.content, rest)) {
        val before = previous.content.dropLast(1)
        val trimmedBefore = before.trimEnd(' ', '\t')
        this[previousIndex] = MarkdownInlineFfi.Text(trimmedBefore)
        val after = rest.drop(1)
        rest = after.trimStart(' ', '\t')
        hadSpace = trimmedBefore.length != before.length || rest.length != after.length
    }
    val lineStart = isAtLineStart()
    rest = rest.withoutRepeatedSeparator(lineStart, (lastOrNull() as? MarkdownInlineFfi.Text)?.content)
    val continues = rest.firstOrNull()?.let { it !in CLOSING_PUNCTUATION } == true
    return if (hadSpace && continues && !lineStart) " $rest" else rest
}

/**
 * Whether [before] ends with an opening bracket or quote that [rest] closes at once, leaving an empty pair. A quote
 * that is its own closer opens a pair only at the start of a word, never as an apostrophe or a closing quote.
 */
private fun closesEmptyPair(
    before: String,
    rest: String,
): Boolean {
    val opener = before.lastOrNull()
    val closer = opener?.let(BRACKET_PAIRS::get)
    val opensHere = opener != closer || before.length == 1 || before[before.length - 2].isWhitespace()
    return closer != null && rest.firstOrNull() == closer && opensHere
}

/**
 * [this] without a leading separator that only followed the removed repeat: nothing is left for it to separate at the
 * start of a line, and the text before already ends with its own punctuation.
 */
private fun String.withoutRepeatedSeparator(
    lineStart: Boolean,
    textBefore: String?,
): String {
    val alreadyPunctuated = textBefore?.lastOrNull()?.let { it in ENDING_PUNCTUATION } == true
    return if ((lineStart || alreadyPunctuated) && firstOrNull()?.let { it in SEPARATORS } == true) {
        drop(1).trimStart(' ', '\t')
    } else {
        this
    }
}

/** Drops the line breaks and blank text a removed final repeat leaves at the end, so no blank last line remains. */
private fun MutableList<MarkdownInlineFfi>.dropTrailingBreaks() {
    while (lastOrNull()?.isBlankTail() == true) removeAt(lastIndex)
}

/** Whether [this] adds nothing visible at the end of a line: a line break or blank text. */
private fun MarkdownInlineFfi.isBlankTail(): Boolean {
    if (this is MarkdownInlineFfi.Text) return content.isBlank()
    return isSoftOrHardBreak()
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

// Separators a removed reference can leave stranded at the start of a line or after the text's own punctuation.
private const val SEPARATORS = ".,;:"

// Punctuation that already ends the text before a removed reference.
private const val ENDING_PUNCTUATION = ".,;:!?"
