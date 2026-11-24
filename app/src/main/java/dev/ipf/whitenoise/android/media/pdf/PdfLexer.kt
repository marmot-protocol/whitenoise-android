package dev.ipf.whitenoise.android.media.pdf

// PDF white-space and delimiter characters (ISO 32000-1, 7.2.2).
private const val NUL = 0
private const val TAB = 9
private const val LF = 10
private const val FF = 12
private const val CR = 13
private const val SPACE = 32
private const val PERCENT = '%'.code
private const val DELIMITERS = "()<>[]{}/%"
private const val ASCII_LIMIT = 128

internal fun isPdfWhitespace(byte: Int): Boolean =
    when (byte) {
        SPACE, LF, CR, TAB, FF, NUL -> true
        else -> false
    }

internal fun isPdfDelimiter(byte: Int): Boolean = byte < ASCII_LIMIT && DELIMITERS.indexOf(byte.toChar()) >= 0

internal fun isPdfRegular(byte: Int): Boolean = !isPdfWhitespace(byte) && !isPdfDelimiter(byte)

internal fun isPdfDigit(byte: Int): Boolean = byte in '0'.code..'9'.code

/** The first index at or after [from] that is neither white space nor inside a comment. */
internal fun PdfBytes.skipSpace(from: Int): Int {
    var index = from
    while (index < size) {
        val byte = get(index)
        when {
            isPdfWhitespace(byte) -> index++
            byte == PERCENT -> {
                while (index < size && get(index) != LF && get(index) != CR) index++
            }
            else -> return index
        }
    }
    return index
}

/** Whether [word] appears at [at]. */
internal fun PdfBytes.matches(
    at: Int,
    word: ByteArray,
): Boolean = at >= 0 && at <= size - word.size && word.indices.all { get(at + it) == (word[it].toInt() and BYTE_MASK) }

/** Whether [word] appears at [at] as a whole token, with no regular character on either side. */
internal fun PdfBytes.isKeywordAt(
    at: Int,
    word: ByteArray,
): Boolean =
    matches(at, word) &&
        (at == 0 || !isPdfRegular(get(at - 1))) &&
        (at + word.size == size || !isPdfRegular(get(at + word.size)))

/** The first index of [word] in [from] until [until], or -1. */
internal fun PdfBytes.indexOf(
    word: ByteArray,
    from: Int,
    until: Int = size,
): Int {
    val first = word[0].toInt() and BYTE_MASK
    var index = from.coerceAtLeast(0)
    val last = until - word.size
    while (index <= last) {
        if (get(index) == first && matches(index, word)) return index
        index++
    }
    return -1
}

/** Where stream data that starts after the `stream` keyword at [keyword] begins: after its CRLF, LF or lone CR. */
internal fun PdfBytes.streamDataStart(keyword: Int): Int {
    var index = keyword + STREAM.size
    if (index < size && get(index) == CR) index++
    if (index < size && get(index) == LF) index++
    return index
}

/** Where data that ends just before the `endstream` keyword at [keyword] ends, dropping its end-of-line marker. */
internal fun PdfBytes.streamDataEnd(
    start: Int,
    keyword: Int,
): Int {
    var index = keyword
    if (index > start && get(index - 1) == LF) index--
    if (index > start && get(index - 1) == CR) index--
    return index
}

internal fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

internal val OBJ = ascii("obj")
internal val STREAM = ascii("stream")
internal val ENDSTREAM = ascii("endstream")
internal val TRAILER = ascii("trailer")
