package dev.ipf.whitenoise.android.media.pdf

private const val MAX_DEPTH = 32
private const val MAX_NAME_LENGTH = 127
private const val HEX_RADIX = 16
private const val NAME_ESCAPE = '#'.code
private const val BACKSLASH = '\\'.code
private const val OPEN_PAREN = '('.code
private const val CLOSE_PAREN = ')'.code
private const val LESS = '<'.code
private const val GREATER = '>'.code
private const val OPEN_BRACKET = '['.code
private const val CLOSE_BRACKET = ']'.code
private const val SLASH = '/'.code
private const val REFERENCE = 'R'.code
private const val NAME_ESCAPE_WIDTH = 3
private const val NUMBER_SIGNS = "+-."
private val DICT_KEYWORDS = setOf("true", "false", "null")

/** Reads PDF values from [bytes]. Every malformed value throws [PdfSyntaxException]; nesting is bounded. */
internal class PdfValueParser(
    private val bytes: PdfBytes,
) {
    /** Parses the value that starts at the first token at or after [from]. */
    @Suppress("CyclomaticComplexMethod") // One branch per PDF value type; splitting it hides the grammar.
    fun parse(
        from: Int,
        depth: Int = 0,
    ): PdfValue {
        if (depth > MAX_DEPTH) pdfSyntaxError()
        val start = bytes.skipSpace(from)
        if (start >= bytes.size) pdfSyntaxError()
        val byte = bytes[start]
        return when {
            byte == LESS && start + 1 < bytes.size && bytes[start + 1] == LESS -> parseDict(start, depth)
            byte == LESS -> parseHexString(start)
            byte == OPEN_PAREN -> parseLiteralString(start)
            byte == OPEN_BRACKET -> parseArray(start, depth)
            byte == SLASH -> parseName(start)
            startsNumber(byte) -> parseNumberOrRef(start)
            isPdfRegular(byte) -> parseKeyword(start)
            else -> pdfSyntaxError()
        }
    }

    private fun parseDict(
        start: Int,
        depth: Int,
    ): PdfDict {
        val entries = mutableListOf<Pair<String, PdfValue>>()
        var index = start + 2
        while (true) {
            index = bytes.skipSpace(index)
            if (index + 1 >= bytes.size) pdfSyntaxError()
            if (bytes[index] == GREATER && bytes[index + 1] == GREATER) return PdfDict(start, index + 2, entries)
            val key = parse(index, depth + 1) as? PdfName ?: pdfSyntaxError()
            val value = parse(key.end, depth + 1)
            if (value is PdfKeyword && value.word !in DICT_KEYWORDS) pdfSyntaxError()
            entries += key.name to value
            index = value.end
        }
    }

    private fun parseArray(
        start: Int,
        depth: Int,
    ): PdfArray {
        val items = mutableListOf<PdfValue>()
        var index = start + 1
        while (true) {
            index = bytes.skipSpace(index)
            if (index >= bytes.size) pdfSyntaxError()
            if (bytes[index] == CLOSE_BRACKET) return PdfArray(start, index + 1, items)
            val item = parse(index, depth + 1)
            items += item
            index = item.end
        }
    }

    private fun parseLiteralString(start: Int): PdfString {
        var index = start + 1
        var open = 1
        while (index < bytes.size) {
            when (bytes[index]) {
                BACKSLASH -> index++
                OPEN_PAREN -> open++
                CLOSE_PAREN -> if (--open == 0) return PdfString(start, index + 1, hex = false)
            }
            index++
        }
        pdfSyntaxError()
    }

    private fun parseHexString(start: Int): PdfString {
        var index = start + 1
        while (index < bytes.size) {
            val byte = bytes[index]
            if (byte == GREATER) return PdfString(start, index + 1, hex = true)
            if (!isPdfWhitespace(byte) && Character.digit(byte, HEX_RADIX) < 0) pdfSyntaxError()
            index++
        }
        pdfSyntaxError()
    }

    private fun parseName(start: Int): PdfName {
        val name = StringBuilder()
        var index = start + 1
        while (index < bytes.size && isPdfRegular(bytes[index])) {
            val byte = bytes[index]
            if (byte == NAME_ESCAPE && index + 2 < bytes.size) {
                val high = Character.digit(bytes[index + 1], HEX_RADIX)
                val low = Character.digit(bytes[index + 2], HEX_RADIX)
                if (high < 0 || low < 0) pdfSyntaxError()
                name.append((high * HEX_RADIX + low).toChar())
                index += NAME_ESCAPE_WIDTH
            } else {
                name.append(byte.toChar())
                index++
            }
            if (name.length > MAX_NAME_LENGTH) pdfSyntaxError()
        }
        return PdfName(start, index, name.toString())
    }

    private fun parseNumberOrRef(start: Int): PdfValue {
        val number = readNumber(start)
        val generationStart = bytes.skipSpace(number.end)
        val objectNumber = number.text.toIntOrNull()
        if (objectNumber == null || generationStart >= bytes.size || !isPdfDigit(bytes[generationStart])) {
            return number
        }
        val generation = readNumber(generationStart)
        val marker = bytes.skipSpace(generation.end)
        val generationNumber = generation.text.toIntOrNull()
        return if (generationNumber != null && isReferenceMarker(marker)) {
            PdfRef(start, marker + 1, objectNumber, generationNumber)
        } else {
            number
        }
    }

    /** Whether the `R` of a reference stands alone at [marker]. */
    private fun isReferenceMarker(at: Int): Boolean = at < bytes.size && bytes[at] == REFERENCE && endsToken(at + 1)

    private fun endsToken(at: Int): Boolean = at == bytes.size || !isPdfRegular(bytes[at])

    private fun readNumber(start: Int): PdfNumber {
        var index = start
        while (index < bytes.size && isPdfRegular(bytes[index])) index++
        val text = String(bytes.copy(start, index), Charsets.ISO_8859_1)
        if (text.toDoubleOrNull() == null) pdfSyntaxError()
        return PdfNumber(start, index, text)
    }

    private fun parseKeyword(start: Int): PdfKeyword {
        var index = start
        while (index < bytes.size && isPdfRegular(bytes[index])) index++
        return PdfKeyword(start, index, String(bytes.copy(start, index), Charsets.ISO_8859_1))
    }
}

private fun startsNumber(byte: Int): Boolean = isPdfDigit(byte) || byte.toChar() in NUMBER_SIGNS
