package dev.ipf.whitenoise.android.media.pdf

private const val MAX_NUMBER_DIGITS = 10
private const val REFERENCE = 'R'.code

/**
 * Counts how often each of [targets] is referred to as `N G R` anywhere in [bytes]: in any object, any revision or
 * any trailer. One linear pass; it reads only plain bytes, so callers add decoded object streams themselves.
 */
internal fun countReferences(
    bytes: PdfBytes,
    targets: Set<Pair<Int, Int>>,
): Map<Pair<Int, Int>, Int> {
    val counts = HashMap<Pair<Int, Int>, Int>()
    var position = 0
    while (position < bytes.size) {
        if (!isPdfDigit(bytes[position]) || (position > 0 && isPdfRegular(bytes[position - 1]))) {
            position++
            continue
        }
        val number = readInteger(bytes, position)
        val reference = number?.let { referenceAfter(bytes, it.second, it.first) }
        if (reference != null && reference.first in targets) {
            counts[reference.first] = (counts[reference.first] ?: 0) + 1
        }
        position = reference?.second ?: number?.second ?: (position + 1)
    }
    return counts
}

/** The `(number, generation)` and end of a reference whose number [number] ends at [from], or null. */
private fun referenceAfter(
    bytes: PdfBytes,
    from: Int,
    number: Int,
): Pair<Pair<Int, Int>, Int>? {
    val generationStart = bytes.skipSpace(from)
    if (generationStart == from || generationStart >= bytes.size || !isPdfDigit(bytes[generationStart])) return null
    val (generation, generationEnd) = readInteger(bytes, generationStart) ?: return null
    val marker = bytes.skipSpace(generationEnd)
    if (marker == generationEnd || marker >= bytes.size || bytes[marker] != REFERENCE) return null
    if (marker + 1 < bytes.size && isPdfRegular(bytes[marker + 1])) return null
    return (number to generation) to (marker + 1)
}

/** The decimal integer starting at [start] and where it ends, or null when it is not a plain integer token. */
private fun readInteger(
    bytes: PdfBytes,
    start: Int,
): Pair<Int, Int>? {
    var end = start
    while (end < bytes.size && isPdfDigit(bytes[end])) end++
    if (end - start > MAX_NUMBER_DIGITS || (end < bytes.size && isPdfRegular(bytes[end]))) return null
    return String(bytes.copy(start, end), Charsets.ISO_8859_1).toIntOrNull()?.let { it to end }
}
