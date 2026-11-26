package dev.ipf.whitenoise.android.media.pdf

private const val MAX_OBJECT_STREAM_BYTES = 16 * 1024 * 1024
private const val MAX_PACKED_OBJECTS = 50_000

/** One decoded object stream (`/Type /ObjStm`) and where each compressed object's value starts in it. */
internal class PdfObjectStream(
    val data: PdfStreamData,
    val offsets: Map<Int, Int>,
) {
    val bytes = BudgetedPdfBytes(ArrayPdfBytes(data.data))
    val strings = mutableListOf<IntRange>()
    private val limits =
        offsets.values
            .sorted()
            .zipWithNext()
            .toMap()
    var changed = false

    fun valueAt(offset: Int): PdfValue {
        val value = PdfValueParser(bytes).parse(offset)
        if (value.end > (limits[offset] ?: bytes.size)) pdfSyntaxError()
        return value
    }
}

/**
 * Decodes every object stream so compressed document-information dictionaries can be found, refusing the plan
 * when one cannot be read within bounds. A signature dictionary found inside one marks the PDF signed.
 */
internal fun decodeObjectStreams(
    bytes: PdfBytes,
    layout: PdfLayout,
    plan: PdfMetadataPlan,
): List<PdfObjectStream> {
    var remainingBytes = MAX_OBJECT_STREAM_BYTES
    var remainingObjects = MAX_PACKED_OBJECTS
    val decoded = mutableListOf<PdfObjectStream>()
    layout.streams.filter { it.dict.name("Type") == "ObjStm" }.forEach { stream ->
        val data = decodeStreamData(bytes, layout, stream, remainingBytes)
        val offsets = data?.let { objectOffsets(it.data, stream.dict, remainingObjects) }
        if (data == null || offsets == null) {
            plan.refuse(PdfMetadataRefusal.Unsupported)
            return decoded
        }
        remainingBytes -= data.data.size
        remainingObjects -= offsets.size
        val objectStream = PdfObjectStream(data, offsets)
        if (containsName(objectStream.bytes, "ByteRange")) plan.refuse(PdfMetadataRefusal.Signed)
        if (containsName(objectStream.bytes, "PieceInfo")) plan.refuse(PdfMetadataRefusal.Unsupported)
        if (plan.refusal != null) return decoded
        decoded += objectStream
    }
    return decoded
}

/** Reads the `/N` number-and-offset pairs at the start of an object stream's [data]. */
@Suppress("ReturnCount") // Each malformed-input check exits at once, so later reads never see unchecked data.)
private fun objectOffsets(
    data: ByteArray,
    dict: PdfDict,
    remainingObjects: Int,
): Map<Int, Int>? {
    val count = (dict["N"] as? PdfNumber)?.intOrNull() ?: return null
    val first = (dict["First"] as? PdfNumber)?.intOrNull() ?: return null
    if (count !in 0..remainingObjects || first !in 0..data.size) return null
    val bytes = ArrayPdfBytes(data)
    val parser = PdfValueParser(bytes)
    val offsets = HashMap<Int, Int>()
    var position = 0
    var previousStart = first - 1
    return try {
        repeat(count) {
            val number = parser.parse(position) as? PdfNumber ?: return null
            val offset = parser.parse(number.end) as? PdfNumber ?: return null
            position = offset.end
            val relative = offset.intOrNull() ?: return null
            if (position > first || relative !in 0 until (data.size - first)) return null
            val start = first + relative
            val objectNumber = number.intOrNull()?.takeIf { it > 0 } ?: return null
            if (start <= previousStart || offsets.put(objectNumber, start) != null) return null
            previousStart = start
        }
        offsets
    } catch (_: PdfSyntaxException) {
        null
    }
}

/** Writes back every changed object stream at its original length, refusing the plan when one does not fit. */
internal fun encodeObjectStreams(
    streams: List<PdfObjectStream>,
    plan: PdfMetadataPlan,
) {
    streams.filter { it.changed }.forEach { stream ->
        val encoded = stream.data.encode(stream.data.data, ByteArray(0))
        if (encoded == null) {
            plan.refuse(PdfMetadataRefusal.Unsupported)
        } else {
            plan.replace(stream.data.stream.dataStart, encoded)
        }
    }
}
