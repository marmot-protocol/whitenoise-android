package dev.ipf.whitenoise.android.core

private const val MAX_FENCE_INDENT = 3
private const val MIN_FENCE_LENGTH = 3

/**
 * Ranges of fenced blocks and inline code spans in [text], sorted and disjoint, for plaintext
 * surfaces that have no Markdown AST. A fence opens on a line indented at most three spaces with
 * three or more backticks (no backtick after them) or tildes, and closes on a later line with at
 * least as many of the same character, or at the end of the text. Between fences, an inline span
 * closes on the next backtick run of exactly its length; an unpaired run stays text.
 *
 * Received text is untrusted, so this is one linear pass: a backtracking pattern took seconds on
 * long backtick runs.
 */
internal fun rawCodeRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var gapStart = 0
    var line = 0
    while (line < text.length) {
        val fenceEnd = fenceEnd(text, line)
        if (fenceEnd != null) {
            addCodeSpans(text, gapStart, line, ranges)
            ranges.add(line until fenceEnd)
            gapStart = fenceEnd
        }
        line = nextLineStart(text, fenceEnd ?: line)
    }
    addCodeSpans(text, gapStart, text.length, ranges)
    return ranges
}

/**
 * A test for whether `[start, end)` misses every one of the sorted, disjoint [ranges]. Calls must
 * come in increasing order of `start`, so a text's matches are checked in one pass.
 */
internal fun outsideRanges(ranges: List<IntRange>): (start: Int, end: Int) -> Boolean {
    var next = 0
    return { start, end ->
        while (next < ranges.size && ranges[next].last < start) {
            next++
        }
        next == ranges.size || ranges[next].first >= end
    }
}

/** Adds the inline code spans of `text[start, end)`, pairing each backtick run with the next run of the same length. */
private fun addCodeSpans(
    text: String,
    start: Int,
    end: Int,
    ranges: MutableList<IntRange>,
) {
    val runs = mutableListOf<IntRange>()
    var index = start
    // Searches stay inside the gap; searching to the end of the text from every gap would be quadratic.
    while (index < end) {
        val runEnd = runEnd(text, index, '`')
        if (runEnd > index) runs.add(index until runEnd)
        index = maxOf(runEnd, index + 1)
    }
    val closers = arrayOfNulls<IntRange>(runs.size)
    // Indexed by run length, which is at most the gap's length, so every lookup is constant time.
    val nextByLength = arrayOfNulls<IntRange>((runs.maxOfOrNull { it.last - it.first + 1 } ?: 0) + 1)
    for (position in runs.indices.reversed()) {
        val run = runs[position]
        val length = run.last - run.first + 1
        closers[position] = nextByLength[length]
        nextByLength[length] = run
    }
    var openAfter = start
    for ((position, run) in runs.withIndex()) {
        val closer = closers[position]
        if (run.first >= openAfter && closer != null) {
            ranges.add(run.first..closer.last)
            openAfter = closer.last + 1
        }
    }
}

/** End (exclusive, before its line break) of the fenced block opening on the line at [lineStart], or null. */
private fun fenceEnd(
    text: String,
    lineStart: Int,
): Int? {
    val markerStart = lineStart + leadingSpaces(text, lineStart)
    val marker = text.getOrNull(markerStart)?.takeIf { it == '`' || it == '~' } ?: return null
    val markerEnd = runEnd(text, markerStart, marker)
    val length = markerEnd - markerStart
    val opens =
        length >= MIN_FENCE_LENGTH &&
            (marker == '~' || (markerEnd until lineEnd(text, markerEnd)).none { text[it] == '`' })
    var line = nextLineStart(text, markerEnd)
    while (opens && line < text.length && !closesFence(text, line, marker, length)) {
        line = nextLineStart(text, line)
    }
    return if (opens) lineEnd(text, line) else null
}

private fun closesFence(
    text: String,
    lineStart: Int,
    marker: Char,
    length: Int,
): Boolean {
    val markerStart = lineStart + leadingSpaces(text, lineStart)
    val markerEnd = runEnd(text, markerStart, marker)
    return markerEnd - markerStart >= length &&
        (markerEnd until lineEnd(text, markerEnd)).all { text[it] == ' ' || text[it] == '\t' }
}

private fun leadingSpaces(
    text: String,
    lineStart: Int,
): Int {
    var count = 0
    while (count < MAX_FENCE_INDENT && text.getOrNull(lineStart + count) == ' ') {
        count++
    }
    return count
}

/** Index of the line break (LF, CR or CRLF) ending the line that contains [from], or the text length. */
private fun lineEnd(
    text: String,
    from: Int,
): Int {
    var end = from.coerceAtMost(text.length)
    while (end < text.length && text[end] != '\n' && text[end] != '\r') {
        end++
    }
    return end
}

/** Start of the line after the one containing [from]. */
private fun nextLineStart(
    text: String,
    from: Int,
): Int {
    val end = lineEnd(text, from)
    return if (text.startsWith("\r\n", end)) end + 2 else end + 1
}

private fun runEnd(
    text: String,
    start: Int,
    char: Char,
): Int {
    var end = start
    while (end < text.length && text[end] == char) {
        end++
    }
    return end
}
