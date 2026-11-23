package dev.ipf.whitenoise.android.media.pdf

private val PACKET_BEGIN = ascii("<?xpacket begin")
private val PACKET_END = ascii("<?xpacket end")
private val PROCESSING_END = ascii("?>")
private const val MAX_PACKET_BYTES = 16 * 1024 * 1024

/**
 * Replaces the content of every XMP metadata stream, document-level or attached to a page, image or font, with
 * [PdfXmpPacket] at the stream's exact length. A stream this app cannot re-encode at that length refuses the plan.
 * Returns the data ranges it covered.
 */
internal fun planMetadataStreams(
    bytes: PdfBytes,
    layout: PdfLayout,
    plan: PdfMetadataPlan,
): List<IntRange> =
    layout.streams
        .filter { it.dict.name("Type") == "Metadata" || it.dict.name("Subtype") == "XML" }
        .map { stream ->
            val data = decodeStreamData(bytes, layout, stream)
            when {
                data == null -> plan.refuse(PdfMetadataRefusal.Unsupported)
                PdfXmpPacket.isReplacement(data.data) -> Unit
                else -> {
                    val packet = PdfXmpPacket.replacing(data.data)
                    val encoded =
                        if (data.compressed) data.encode(packet.head, packet.tail) else packet.fitted(data.data.size)
                    // A packet that does not fit its stream is refused rather than left as invalid XML.
                    if (encoded == null) {
                        plan.refuse(PdfMetadataRefusal.Unsupported)
                    } else {
                        plan.fields += PdfMetadataField.Xmp
                        plan.replace(stream.dataStart, encoded)
                    }
                }
            }
            stream.dataStart until stream.dataEnd
        }

/**
 * Replaces any other XMP packet stored in an unfiltered stream, such as one in a stream that does not declare itself
 * as metadata, with [PdfXmpPacket] at the same length. Each packet must open and close inside one of [plainStreams];
 * one that does not, or that has no end, refuses the plan because its extent cannot be trusted. [covered] ranges
 * were already handled.
 */
internal fun planLoosePackets(
    bytes: PdfBytes,
    plainStreams: List<IntRange>,
    covered: List<IntRange>,
    plan: PdfMetadataPlan,
) {
    var position = bytes.indexOf(PACKET_BEGIN, 0)
    while (position >= 0 && plan.refusal == null) {
        val begin = position
        val handled = covered.firstOrNull { begin in it }
        if (handled != null) {
            position = bytes.indexOf(PACKET_BEGIN, handled.last + 1)
            continue
        }
        val stream = plainStreams.firstOrNull { begin in it }
        val end = stream?.let { packetEnd(bytes, begin, it.last + 1) } ?: -1
        val replacement = if (end < 0) null else replacementFor(bytes.copy(begin, end))
        when {
            end < 0 || replacement == null -> plan.refuse(PdfMetadataRefusal.Unsupported)
            replacement.isEmpty() -> Unit
            else -> {
                plan.fields += PdfMetadataField.Xmp
                plan.replace(begin, replacement)
            }
        }
        position = if (end < 0) -1 else bytes.indexOf(PACKET_BEGIN, end)
    }
}

/** The same-length replacement for [packet], empty when it already is one, or null when none fits. */
private fun replacementFor(packet: ByteArray): ByteArray? =
    if (PdfXmpPacket.isReplacement(packet)) ByteArray(0) else PdfXmpPacket.replacing(packet).fitted(packet.size)

/** Where the packet opened at [begin] ends, just after its `<?xpacket end ... ?>`, before [limit], or -1. */
private fun packetEnd(
    bytes: PdfBytes,
    begin: Int,
    limit: Int,
): Int {
    val bound = minOf(limit, begin + MAX_PACKET_BYTES)
    val end = bytes.indexOf(PACKET_END, begin, bound)
    if (end < 0) return -1
    val close = bytes.indexOf(PROCESSING_END, end, minOf(bound, end + PACKET_END.size + 32))
    return if (close < 0) -1 else close + PROCESSING_END.size
}
