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
 * Replaces any other plainly stored XMP packet, such as one in an uncompressed stream that does not declare itself
 * as metadata, with [PdfXmpPacket] at the same length. A packet with no end is left to the reader, since nothing
 * marks where it stops. [covered] ranges were already handled.
 */
internal fun planLoosePackets(
    bytes: PdfBytes,
    covered: List<IntRange>,
    plan: PdfMetadataPlan,
) {
    var position = bytes.indexOf(PACKET_BEGIN, 0)
    while (position >= 0) {
        val end = packetEnd(bytes, position)
        if (end < 0) return
        if (covered.none { position in it }) {
            val packet = bytes.copy(position, end)
            if (!PdfXmpPacket.isReplacement(packet)) {
                plan.fields += PdfMetadataField.Xmp
                plan.replace(position, PdfXmpPacket.replacing(packet).fitted(packet.size))
            }
        }
        position = bytes.indexOf(PACKET_BEGIN, end)
    }
}

/** Where the packet opened at [begin] ends, just after its `<?xpacket end ... ?>`, or -1. */
private fun packetEnd(
    bytes: PdfBytes,
    begin: Int,
): Int {
    val limit = minOf(bytes.size, begin + MAX_PACKET_BYTES)
    val end = bytes.indexOf(PACKET_END, begin, limit)
    if (end < 0) return -1
    val close = bytes.indexOf(PROCESSING_END, end, minOf(limit, end + PACKET_END.size + 32))
    return if (close < 0) -1 else close + PROCESSING_END.size
}
