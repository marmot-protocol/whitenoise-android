package dev.ipf.whitenoise.android.media.pdf

private const val MARKER = 0xFF
private const val SOI = 0xD8
private const val SOS = 0xDA
private const val EOI = 0xD9
private const val TEM = 0x01
private const val RST0 = 0xD0
private const val RST7 = 0xD7
private const val APP1 = 0xE1
private const val APP3 = 0xE3
private const val APP13 = 0xED
private const val APP15 = 0xEF
private const val COM = 0xFE
private const val SEGMENT_HEADER = 4
private val PRIVATE_MARKERS = setOf(APP1, APP15, COM) + (APP3..APP13)
private const val LENGTH_SHIFT = 8

/**
 * Finds the private segments of a JPEG embedded as a `/DCTDecode` image: EXIF and XMP (APP1), IPTC and
 * Photoshop records (APP13), vendor segments and comments. Their payloads are zeroed in place, which keeps every
 * segment length and leaves only empty segments that decoders skip. JFIF (APP0), ICC colour profiles (APP2) and
 * Adobe colour transforms (APP14) stay, because the image renders with them.
 */
internal object PdfJpegMetadata {
    /**
     * Adds zeroing edits for the JPEG in [start] until [end] to [plan], walking every segment through EOI, including
     * those between scans. Returns false when it is malformed or ends before EOI.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // One exit per JPEG marker class, in one loop.
    fun plan(
        bytes: PdfBytes,
        start: Int,
        end: Int,
        plan: PdfMetadataPlan,
    ): Boolean {
        if (end - start < 2 || bytes[start] != MARKER || bytes[start + 1] != SOI) return false
        var position = start + 2
        while (position + 2 <= end) {
            if (bytes[position] != MARKER) return false
            val marker = bytes[position + 1]
            when {
                marker == MARKER -> position++
                marker == EOI -> {
                    position += 2
                    if (position == end) return true
                    // MPF and gain-map JPEGs can store another complete image here. Unrecognized tails
                    // (for example motion-photo video) cannot be safely rewritten as a JPEG.
                    if (position + 2 > end || bytes[position] != MARKER || bytes[position + 1] != SOI) return false
                    position += 2
                }
                marker == TEM || marker in RST0..RST7 -> position += 2
                position + SEGMENT_HEADER > end -> return false
                else -> {
                    val length = (bytes[position + 2] shl LENGTH_SHIFT) or bytes[position + 3]
                    if (length < 2 || position + 2 + length > end) return false
                    if (isPrivate(marker)) plan.zero(bytes, position + SEGMENT_HEADER, length - 2)
                    position += 2 + length
                    if (marker == SOS) position = nextMarker(bytes, position, end)
                }
            }
        }
        return false
    }

    /** The next marker after entropy-coded data from [from]: `FF` not followed by stuffing `00` or a restart. */
    private fun nextMarker(
        bytes: PdfBytes,
        from: Int,
        end: Int,
    ): Int {
        var position = from
        while (position + 1 < end) {
            val next = bytes[position + 1]
            if (bytes[position] == MARKER && endsEntropyData(next)) return position
            position++
        }
        return end
    }

    /** Whether `FF` followed by [next] is a marker rather than stuffing or a restart inside a scan. */
    private fun endsEntropyData(next: Int): Boolean = next != 0 && next != MARKER && next !in RST0..RST7

    private fun isPrivate(marker: Int): Boolean = marker in PRIVATE_MARKERS
}
