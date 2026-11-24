package dev.ipf.whitenoise.android.media.pdf

private const val PERCENT = '%'.code
private const val LF = 10
private const val CR = 13

/**
 * Walks every `N G obj` and `trailer` in [source] in file order, which covers every revision an incremental update
 * left behind. Comments between objects are skipped. Stream data is skipped by its direct `/Length` when that is
 * exact, and by its `endstream` otherwise, so binary data is not read as syntax. An object that does not parse is
 * passed over but remembered, so the plan can refuse when it needed that object. All reads share one work budget.
 */
internal class PdfObjectWalk(
    source: PdfBytes,
) {
    private val bytes = BudgetedPdfBytes(source)
    private val parser = PdfValueParser(bytes)
    private val index = PdfObjectIndex()
    private val trailers = mutableListOf<PdfDict>()
    private val streams = mutableListOf<PdfStream>()
    private val plainStreams = mutableListOf<IntRange>()
    private val unreadable = mutableSetOf<Int>()
    private val infoLike = mutableListOf<Pair<Int, Int>>()
    private val embeddedInfo = mutableListOf<PdfValue>()
    private val guessed = mutableListOf<PdfGuessedStream>()

    // Once one stream has no `endstream` after it, no later stream can have one, so later searches stop at once.
    private var endstreamMissingFrom = Int.MAX_VALUE

    fun walk(): PdfLayout {
        var position = 0
        while (position < bytes.size - OBJ.size) {
            val byte = bytes[position]
            position =
                when {
                    byte == PERCENT -> endOfLine(position)
                    byte == 'o'.code && bytes.isKeywordAt(position, OBJ) -> visitObject(position)
                    byte == 't'.code && bytes.isKeywordAt(position, TRAILER) -> visitTrailer(position)
                    else -> position + 1
                }
        }
        return PdfLayout(index, trailers, streams, plainStreams, unreadable, infoLike, embeddedInfo, guessed)
    }

    private fun endOfLine(from: Int): Int {
        var position = from
        while (position < bytes.size && bytes[position] != LF && bytes[position] != CR) position++
        return position
    }

    private fun visitTrailer(keyword: Int): Int =
        try {
            val dict = parser.parse(keyword + TRAILER.size) as? PdfDict ?: return keyword + TRAILER.size
            trailers += dict
            dict.end
        } catch (_: PdfSyntaxException) {
            keyword + TRAILER.size
        }

    @Suppress("ReturnCount") // Not an object, unreadable, a plain value, a dictionary, or a stream.)
    private fun visitObject(keyword: Int): Int {
        val next = keyword + OBJ.size
        val (number, generation) = objectHeaderBefore(keyword) ?: return next
        val value =
            try {
                parser.parse(next)
            } catch (_: PdfSyntaxException) {
                unreadable += number
                return next
            }
        index.add(number, generation, value.start)
        if (value !is PdfDict) return value.end
        value.entries.forEach { (key, entry) -> if (key.startsWith("PTEX.")) embeddedInfo += entry }
        val streamKeyword = bytes.skipSpace(value.end)
        if (!bytes.isKeywordAt(streamKeyword, STREAM)) {
            if (isInfoLike(value)) infoLike += number to generation
            return value.end
        }
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
        if (verifiedEnd == null) guessed += PdfGuessedStream(dict["Length"], dataStart, endKeyword)
        if (endKeyword < 0) {
            unreadable += number
            return dataStart
        }
        val dataEnd = verifiedEnd ?: bytes.streamDataEnd(dataStart, endKeyword)
        if (dict.name("Type") == "XRef") trailers += dict
        if (dict.filters()?.isEmpty() == true) plainStreams += dataStart until dataEnd
        if (isInteresting(dict)) streams += PdfStream(number, dict, dataStart, dataEnd, exact = verifiedEnd != null)
        return endKeyword + ENDSTREAM.size
    }

    private fun isInteresting(dict: PdfDict): Boolean {
        val type = dict.name("Type")
        return type == "ObjStm" ||
            type == "Metadata" ||
            dict.name("Subtype") == "XML" ||
            (dict.name("Subtype") == "Image" && dict.filters()?.any { it == "DCTDecode" || it == "DCT" } != false)
    }

    /** Reads `N G` back from the `obj` keyword at [keyword], or null when the keyword does not open an object. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Reads two tokens backwards; each check exits at once.)
    private fun objectHeaderBefore(keyword: Int): Pair<Int, Int>? {
        var position = keyword - 1
        if (position < 0 || !isPdfWhitespace(bytes[position])) return null
        while (position >= 0 && isPdfWhitespace(bytes[position])) position--
        val generationEnd = position + 1
        while (position >= 0 && isPdfDigit(bytes[position])) position--
        val generationStart = position + 1
        if (generationStart == generationEnd || position < 0 || !isPdfWhitespace(bytes[position])) return null
        while (position >= 0 && isPdfWhitespace(bytes[position])) position--
        val numberEnd = position + 1
        while (position >= 0 && isPdfDigit(bytes[position])) position--
        val numberStart = position + 1
        if (numberStart == numberEnd || (position >= 0 && isPdfRegular(bytes[position]))) return null
        val number = String(bytes.copy(numberStart, numberEnd), Charsets.ISO_8859_1).toIntOrNull() ?: return null
        val generation =
            String(bytes.copy(generationStart, generationEnd), Charsets.ISO_8859_1).toIntOrNull() ?: return null
        return number to generation
    }
}
