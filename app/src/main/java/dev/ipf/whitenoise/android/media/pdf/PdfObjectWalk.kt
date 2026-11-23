package dev.ipf.whitenoise.android.media.pdf

private const val INDEX_SHIFT = 32
private const val LOW_BITS = 0xFFFFFFFFL
private const val MAX_OBJECTS = 8_000_000

/** A stream the metadata plan reads: its object, its dictionary and its data range. */
internal class PdfStream(
    val objectNumber: Int,
    val dict: PdfDict,
    val dataStart: Int,
    val dataEnd: Int,
    /** Whether a direct `/Length` confirmed [dataEnd]; otherwise it was found from `endstream`. */
    val exact: Boolean,
)

/** What one walk over an uncompressed PDF file found. */
internal class PdfLayout(
    private val index: LongArray,
    val trailers: List<PdfDict>,
    val streams: List<PdfStream>,
    val signed: Boolean,
) {
    /** Where the values of every uncompressed object numbered [number] start, oldest revision first. */
    fun positions(number: Int): List<Int> {
        var low = 0
        var high = index.size
        val key = number.toLong() shl INDEX_SHIFT
        while (low < high) {
            val middle = (low + high) ushr 1
            if (index[middle] < key) low = middle + 1 else high = middle
        }
        val found = mutableListOf<Int>()
        while (low < index.size && (index[low] ushr INDEX_SHIFT) == number.toLong()) {
            found += (index[low] and LOW_BITS).toInt()
            low++
        }
        return found
    }
}

/**
 * Walks every `N G obj` and `trailer` in [bytes] in file order, which covers every revision an incremental update
 * left behind. Stream data is skipped by its direct `/Length` when that is exact, and by its `endstream` otherwise,
 * so binary data is not read as syntax. An object that does not parse is passed over.
 */
internal class PdfObjectWalk(
    private val bytes: PdfBytes,
) {
    private val parser = PdfValueParser(bytes)
    private var index = LongArray(INITIAL_OBJECTS)
    private var objectCount = 0
    private val trailers = mutableListOf<PdfDict>()
    private val streams = mutableListOf<PdfStream>()
    private var signed = false

    // Once one stream has no `endstream` after it, no later stream can have one, so later searches stop at once.
    private var endstreamMissingFrom = Int.MAX_VALUE

    fun walk(): PdfLayout {
        var position = 0
        while (position < bytes.size - OBJ.size) {
            val byte = bytes[position]
            position =
                when {
                    byte == 'o'.code && bytes.isKeywordAt(position, OBJ) -> visitObject(position)
                    byte == 't'.code && bytes.isKeywordAt(position, TRAILER) -> visitTrailer(position)
                    else -> position + 1
                }
        }
        val sorted = index.copyOf(objectCount).also { it.sort() }
        return PdfLayout(sorted, trailers, streams, signed)
    }

    private fun visitTrailer(keyword: Int): Int =
        try {
            val dict = parser.parse(keyword + TRAILER.size) as? PdfDict ?: return keyword + TRAILER.size
            trailers += dict
            dict.end
        } catch (_: PdfSyntaxException) {
            keyword + TRAILER.size
        }

    private fun visitObject(keyword: Int): Int {
        val next = keyword + OBJ.size
        val number = objectNumberBefore(keyword) ?: return next
        val value =
            try {
                parser.parse(next)
            } catch (_: PdfSyntaxException) {
                return next
            }
        record(number, value.start)
        if (value !is PdfDict) return value.end
        if (value.has("ByteRange")) signed = true
        val streamKeyword = bytes.skipSpace(value.end)
        if (!bytes.isKeywordAt(streamKeyword, STREAM)) return value.end
        return visitStream(number, value, streamKeyword)
    }

    private fun visitStream(
        number: Int,
        dict: PdfDict,
        keyword: Int,
    ): Int {
        val dataStart = bytes.streamDataStart(keyword)
        val declared = (dict["Length"] as? PdfNumber)?.intOrNull()
        val verifiedEnd =
            declared
                ?.takeIf { it >= 0 && dataStart.toLong() + it <= bytes.size }
                ?.let { dataStart + it }
                ?.takeIf { bytes.matches(bytes.skipSpace(it), ENDSTREAM) }
        val endKeyword =
            when {
                verifiedEnd != null -> bytes.skipSpace(verifiedEnd)
                dataStart >= endstreamMissingFrom -> -1
                else -> bytes.indexOf(ENDSTREAM, dataStart).also { if (it < 0) endstreamMissingFrom = dataStart }
            }
        if (endKeyword < 0) return dataStart
        val dataEnd = verifiedEnd ?: bytes.streamDataEnd(dataStart, endKeyword)
        if (dict.name("Type") == "XRef") trailers += dict
        if (isInteresting(dict)) streams += PdfStream(number, dict, dataStart, dataEnd, exact = verifiedEnd != null)
        return endKeyword + ENDSTREAM.size
    }

    private fun isInteresting(dict: PdfDict): Boolean {
        val type = dict.name("Type")
        return type == "ObjStm" || type == "Metadata" || dict.name("Subtype") == "XML" ||
            (dict.name("Subtype") == "Image" && dict.filters()?.any { it == "DCTDecode" || it == "DCT" } != false)
    }

    /** Reads `N G` back from the `obj` keyword at [keyword], or null when the keyword does not open an object. */
    private fun objectNumberBefore(keyword: Int): Int? {
        var position = keyword - 1
        if (position < 0 || !isPdfWhitespace(bytes[position])) return null
        while (position >= 0 && isPdfWhitespace(bytes[position])) position--
        val generationEnd = position + 1
        while (position >= 0 && isPdfDigit(bytes[position])) position--
        if (position + 1 == generationEnd || position < 0 || !isPdfWhitespace(bytes[position])) return null
        while (position >= 0 && isPdfWhitespace(bytes[position])) position--
        val numberEnd = position + 1
        while (position >= 0 && isPdfDigit(bytes[position])) position--
        val numberStart = position + 1
        if (numberStart == numberEnd || (position >= 0 && isPdfRegular(bytes[position]))) return null
        return String(bytes.copy(numberStart, numberEnd), Charsets.ISO_8859_1).toIntOrNull()
    }

    private fun record(
        number: Int,
        position: Int,
    ) {
        if (objectCount == MAX_OBJECTS) pdfSyntaxError()
        if (objectCount == index.size) index = index.copyOf(index.size * 2)
        index[objectCount++] = (number.toLong() shl INDEX_SHIFT) or position.toLong()
    }

    private companion object {
        const val INITIAL_OBJECTS = 256
    }
}
