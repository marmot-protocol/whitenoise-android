package dev.ipf.whitenoise.android.media.pdf

private const val MAX_OBJECT_STREAM_BYTES = 256L * 1024 * 1024
private val BYTE_RANGE = ascii("/ByteRange")

/** One decoded object stream (`/Type /ObjStm`) and where each compressed object's value starts in it. */
internal class PdfObjectStream(
    val data: PdfStreamData,
    val offsets: Map<Int, Int>,
) {
    val bytes = ArrayPdfBytes(data.data)
    var changed = false
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
    var total = 0L
    val decoded = mutableListOf<PdfObjectStream>()
    for (stream in layout.streams.filter { it.dict.name("Type") == "ObjStm" }) {
        val data = decodeStreamData(bytes, layout, stream)
        total += data?.data?.size ?: 0
        val offsets = data?.let { objectOffsets(it.data, stream.dict) }
        if (data == null || offsets == null || total > MAX_OBJECT_STREAM_BYTES) {
            plan.refuse(PdfMetadataRefusal.Unsupported)
            break
        }
        val objectStream = PdfObjectStream(data, offsets)
        // A plain search is enough here: a false match only refuses, which leaves the file unchanged.
        if (objectStream.bytes.indexOf(BYTE_RANGE, 0) >= 0) plan.refuse(PdfMetadataRefusal.Signed)
        decoded += objectStream
    }
    return decoded
}

/** Reads the `/N` number-and-offset pairs at the start of an object stream's [data]. */
private fun objectOffsets(
    data: ByteArray,
    dict: PdfDict,
): Map<Int, Int>? {
    val count = (dict["N"] as? PdfNumber)?.intOrNull() ?: return null
    val first = (dict["First"] as? PdfNumber)?.intOrNull() ?: return null
    if (count < 0 || first < 0 || first > data.size) return null
    val bytes = ArrayPdfBytes(data)
    val parser = PdfValueParser(bytes)
    val offsets = HashMap<Int, Int>()
    var position = 0
    return try {
        repeat(count) {
            val number = parser.parse(position) as? PdfNumber ?: return null
            val offset = parser.parse(number.end) as? PdfNumber ?: return null
            position = offset.end
            val start = first + (offset.intOrNull() ?: return null)
            if (start !in first until data.size) return null
            offsets[number.intOrNull() ?: return null] = start
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
