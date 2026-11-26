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
            rawEnd(source, index).let { LiteralSpan(index + RAW_QUOTE.length until it - RAW_QUOTE.length, it) }
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

/**
 * The index of the quote that closes the one-line literal opened at [open], skipping escapes and, in a string,
 * whole `${…}` templates, whose expressions may hold quotes of their own.
 */
private fun quotedEnd(
    source: String,
    open: Int,
): Int {
    var end = open + 1
    while (end < source.length && source[end] != source[open]) {
        end =
            when {
                source[end] == '\\' -> end + 2
                source[open] == '"' && source.startsWith("\${", end) -> templateEnd(source, end + 1)
                else -> end + 1
            }
    }
    return end.coerceAtMost(source.length)
}

/**
 * The index just past the `"""` that closes the raw string opened at [open], skipping whole templates. Quotes just
 * before the closing three belong to the string, so the whole run of quotes is consumed.
 */
private fun rawEnd(
    source: String,
    open: Int,
): Int {
    var index = open + RAW_QUOTE.length
    while (index < source.length && !source.startsWith(RAW_QUOTE, index)) {
        index = if (source.startsWith("\${", index)) templateEnd(source, index + 1) else index + 1
    }
    while (index < source.length && source[index] == '"') index++
    return index
}

/** The index just past the brace that closes the template opened at [open], stepping over literals and comments. */
private fun templateEnd(
    source: String,
    open: Int,
): Int {
    var depth = 0
    var index = open
    while (index < source.length) {
        val literal = literalSpan(source, index)
        when {
            literal != null -> index = literal.next
            source[index] == '{' -> {
                depth++
                index++
            }
            source[index] == '}' -> if (--depth == 0) return index + 1 else index++
            else -> index++
        }
    }
    return source.length
}

private const val RAW_QUOTE = "\"\"\""
