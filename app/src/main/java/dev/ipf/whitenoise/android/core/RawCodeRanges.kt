package dev.ipf.whitenoise.android.core

private const val MAX_FENCE_INDENT = 3
private const val MIN_FENCE_LENGTH = 3

/**
 * Ranges of fenced blocks and inline code spans in [text], sorted and disjoint, for plaintext
 * surfaces that have no Markdown AST. A fence opens on a line indented at most three spaces with
 * three or more backticks (no backtick after them) or tildes, and closes on a later line with at
 * least as many of the same character, or at the end of the text. An inline span closes on the
 * next backtick run of exactly its length; an unpaired run stays text.
 *
 * Received text is untrusted, so this is one linear pass: a backtracking pattern took seconds on
 * long backtick runs.
 */
internal fun rawCodeRanges(text: String): List<IntRange> {
    val closers = closingBacktickRuns(text)
    val ranges = mutableListOf<IntRange>()
    var index = 0
    while (index < text.length) {
        val fenceEnd = if (index == 0 || text[index - 1] == '\n') fenceEnd(text, index) else null
        val closer = if (text[index] == '`') closers[index] else null
        val end =
            when {
                fenceEnd != null -> fenceEnd
                closer != null -> closer + runEnd(text, index, '`') - index
                else -> null
            }
        end?.let { ranges.add(index until it) }
        index = end ?: if (text[index] == '`') runEnd(text, index, '`') else index + 1
    }
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

/** For each backtick run's start, the start of the next run with exactly the same length. */
private fun closingBacktickRuns(text: String): Map<Int, Int> {
    val runs = mutableListOf<IntRange>()
    var index = text.indexOf('`')
    while (index >= 0) {
        val end = runEnd(text, index, '`')
        runs.add(index until end)
        index = text.indexOf('`', end)
    }
    val closers = HashMap<Int, Int>()
    val nextByLength = HashMap<Int, Int>()
    for (run in runs.asReversed()) {
        val length = run.last - run.first + 1
        nextByLength[length]?.let { closers[run.first] = it }
        nextByLength[length] = run.first
    }
    return closers
}

/** End (exclusive) of the fenced block opening on the line at [lineStart], or null when no fence opens there. */
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
    var line = lineEnd(text, markerEnd) + 1
    while (opens && line < text.length && !closesFence(text, line, marker, length)) {
        line = lineEnd(text, line) + 1
    }
    return if (opens) lineEnd(text, line) else null
}

private fun closesFence(
    text: String,
    lineStart: Int,
    marker: Char,
    length: Int,
): Boolean {
    val lineEnd = lineEnd(text, lineStart)
    val markerStart = lineStart + leadingSpaces(text, lineStart)
    val markerEnd = runEnd(text, markerStart, marker)
    return markerEnd - markerStart >= length &&
        text.substring(markerEnd, lineEnd).removeSuffix("\r").all { it == ' ' || it == '\t' }
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

private fun lineEnd(
    text: String,
    from: Int,
): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

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
