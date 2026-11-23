package dev.ipf.whitenoise.android.media.pdf

private const val MAX_DECODED_STREAM = 32 * 1024 * 1024
private const val EOL_SLACK = 2

/** Decoded data of one stream and how to write it back at exactly its original length. */
internal class PdfStreamData(
    val stream: PdfStream,
    val end: Int,
    val compressed: Boolean,
    val data: ByteArray,
) {
    /** The bytes that replace the stream's data so it decodes to [prefix], padding spaces, then [suffix]. */
    fun encode(
        prefix: ByteArray,
        suffix: ByteArray,
    ): ByteArray? {
        val length = end - stream.dataStart
        if (!compressed) {
            if (prefix.size + suffix.size > length) return null
            return prefix + ByteArray(length - prefix.size - suffix.size) { ' '.code.toByte() } + suffix
        }
        return PdfFlate.encodeExactly(prefix, suffix, length)
    }
}

/**
 * Reads [stream]'s data when it is stored plainly or with `/FlateDecode` alone and no predictor, returning null
 * for any other encoding, data that does not inflate within bounds, or a length that cannot be confirmed. An
 * indirect `/Length` is resolved through [layout].
 */
internal fun decodeStreamData(
    bytes: PdfBytes,
    layout: PdfLayout,
    stream: PdfStream,
): PdfStreamData? {
    val filters = stream.dict.filters() ?: return null
    val compressed =
        when {
            filters.isEmpty() -> false
            filters == listOf("FlateDecode") || filters == listOf("Fl") -> true
            else -> return null
        }
    if (stream.dict["DecodeParms"].let { it != null && !(it is PdfKeyword && it.word == "null") }) return null
    val end = exactStreamEnd(bytes, layout, stream) ?: return null
    val raw = bytes.copy(stream.dataStart, end)
    val data = if (compressed) PdfFlate.inflate(raw, MAX_DECODED_STREAM) ?: return null else raw
    return PdfStreamData(stream, end, compressed, data)
}

/** Where [stream]'s data ends by its `/Length`, resolving an indirect length, or null when it cannot be known. */
private fun exactStreamEnd(
    bytes: PdfBytes,
    layout: PdfLayout,
    stream: PdfStream,
): Int? {
    if (stream.exact) return stream.dataEnd
    val reference = stream.dict["Length"] as? PdfRef ?: return null
    val position = layout.positions(reference.number).lastOrNull() ?: return null
    val length = (PdfValueParser(bytes).parse(position) as? PdfNumber)?.intOrNull() ?: return null
    val end = stream.dataStart.toLong() + length
    return end.toInt().takeIf { length >= 0 && end in stream.dataEnd.toLong()..stream.dataEnd.toLong() + EOL_SLACK }
}
