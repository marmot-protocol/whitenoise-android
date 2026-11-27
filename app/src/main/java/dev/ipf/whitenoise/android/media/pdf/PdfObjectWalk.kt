package dev.ipf.whitenoise.android.media.pdf

private const val PERCENT = '%'.code
private const val LF = 10
private const val CR = 13

/**
 * Walks every `N G obj` and `trailer` in [bytes] in file order, which covers every revision an incremental update
 * left behind. Comments between objects are skipped. Stream data is skipped by its direct `/Length` when that is
 * exact, and by its `endstream` otherwise, so binary data is not read as syntax. An object that does not parse is
 * passed over but remembered, so the plan can refuse when it needed that object. All reads share one work budget.
 */
internal class PdfObjectWalk(
    private val bytes: PdfBytes,
) {
    private val parser = PdfValueParser(bytes)
    private val index = PdfObjectIndex()
    private val trailers = mutableListOf<PdfDict>()
    private val streams = mutableListOf<PdfStream>()
    private val plainStreams = mutableListOf<IntRange>()
    private val unreadable = mutableSetOf<Int>()
    private val infoLike = mutableListOf<Pair<Int, Int>>()
    private val embeddedInfo = mutableListOf<PdfValue>()
    private val strings = mutableListOf<IntRange>()
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
                    isPdfDigit(byte) && (position == 0 || !isPdfRegular(bytes[position - 1])) -> visitObject(position)
                    byte == 't'.code && bytes.isKeywordAt(position, TRAILER) -> visitTrailer(position)
                    else -> position + 1
                }
        }
        return PdfLayout(index, trailers, streams, plainStreams, unreadable, infoLike, embeddedInfo, guessed, strings)
    }

    private fun endOfLine(from: Int): Int {
        var position = from
        while (position < bytes.size && bytes[position] != LF && bytes[position] != CR) position++
        return position
    }

    private fun visitTrailer(keyword: Int): Int =
        try {
            val dict = parser.parse(keyword + TRAILER.size) as? PdfDict ?: return keyword + TRAILER.size
            dict.collectStringRanges(strings)
            trailers += dict
            dict.end
        } catch (_: PdfSyntaxException) {
            keyword + TRAILER.size
        }

    @Suppress("ReturnCount") // A number outside an object header is skipped without parsing a value.
    private fun visitObject(start: Int): Int {
        val numberEnd = digitEnd(start)
        val generationStart = bytes.skipSpace(numberEnd)
        if (generationStart == numberEnd) return numberEnd
        val generationEnd = digitEnd(generationStart)
        if (generationEnd == generationStart) return numberEnd
        val keyword = bytes.skipSpace(generationEnd)
        if (keyword == generationEnd || !bytes.isKeywordAt(keyword, OBJ)) return numberEnd
        val number = headerInt(start, numberEnd) ?: return numberEnd
        val generation = headerInt(generationStart, generationEnd) ?: return numberEnd
        val next = keyword + OBJ.size
        val value =
            try {
                parser.parse(next)
            } catch (_: PdfSyntaxException) {
                unreadable += number
                return next
            }
        value.collectStringRanges(strings)
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

    private fun digitEnd(start: Int): Int {
        var end = start
        while (end < bytes.size && isPdfDigit(bytes[end])) end++
        return end
    }

    private fun headerInt(
        start: Int,
        end: Int,
    ): Int? =
        if (end - start > Int.MAX_VALUE.toString().length) {
            null
        } else {
            String(bytes.copy(start, end), Charsets.ISO_8859_1).toIntOrNull()
        }
}
