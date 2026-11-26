package dev.ipf.whitenoise.android.ui.common

/** [source] with comments and the contents of string and character literals blanked, offsets unchanged. */
internal fun blankCommentsAndStrings(source: String): String {
    val out = StringBuilder(source)
    var index = 0
    while (index < source.length) {
        val span = literalSpan(source, index)
        if (span == null) {
            index++
        } else {
            for (position in span.blank) if (source[position] != '\n') out[position] = ' '
            index = span.next
        }
    }
    return out.toString()
}

/** A comment or literal: the range to blank, and the index where scanning resumes after it. */
private class LiteralSpan(
    val blank: IntRange,
    val next: Int,
)

/** The comment or literal that starts at [index] in [source], or null when none does. */
private fun literalSpan(
    source: String,
    index: Int,
): LiteralSpan? =
    when {
        source.startsWith("//", index) -> comment(index, source.indexOrEnd("\n", index, past = false))
        source.startsWith("/*", index) -> comment(index, source.blockCommentEnd(index))
        source.startsWith(RAW_QUOTE, index) ->
            source.indexOrEnd(RAW_QUOTE, index + RAW_QUOTE.length, past = true).let {
                LiteralSpan(index + RAW_QUOTE.length until it - RAW_QUOTE.length, it)
            }
        source[index] == '"' || source[index] == '\'' ->
            quotedEnd(source, index).let { LiteralSpan(index + 1 until it, it + 1) }
        else -> null
    }

/** Kotlin block comments may contain other block comments. Returns the end of the outer comment. */
private fun String.blockCommentEnd(open: Int): Int {
    var depth = 1
    var index = open + 2
    while (index < length && depth > 0) {
        when {
            startsWith("/*", index) -> {
                depth++
                index += 2
            }
            startsWith("*/", index) -> {
                depth--
                index += 2
            }
            else -> index++
        }
    }
    return index
}

/** A comment from [start] until [end], blanked whole. */
private fun comment(
    start: Int,
    end: Int,
) = LiteralSpan(start until end, end)

/** The index of [marker] from [from], past it when [past], or the end of the text when it is missing. */
private fun String.indexOrEnd(
    marker: String,
    from: Int,
    past: Boolean,
): Int {
    val found = indexOf(marker, from)
    return when {
        found < 0 -> length
        past -> found + marker.length
        else -> found
    }
}

/** The index of the quote that closes the one-line literal opened at [open], skipping escapes. */
private fun quotedEnd(
    source: String,
    open: Int,
): Int {
    var end = open + 1
    while (end < source.length && source[end] != source[open]) end += if (source[end] == '\\') 2 else 1
    return end.coerceAtMost(source.length)
}

private const val RAW_QUOTE = "\"\"\""
