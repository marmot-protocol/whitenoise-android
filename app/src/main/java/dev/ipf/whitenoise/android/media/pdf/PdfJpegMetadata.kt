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
private const val LENGTH_SHIFT = 8

/**
 * Finds the private segments of a baseline JPEG embedded as a `/DCTDecode` image: EXIF and XMP (APP1), IPTC and
 * Photoshop records (APP13), vendor segments and comments. Their payloads are zeroed in place, which keeps every
 * segment length and leaves only empty segments that decoders skip. JFIF (APP0), ICC colour profiles (APP2) and
 * Adobe colour transforms (APP14) stay, because the image renders with them.
 */
internal object PdfJpegMetadata {
    /** Adds zeroing edits for the JPEG in [start] until [end] to [plan], or returns false when it is malformed. */
    fun plan(
        bytes: PdfBytes,
        start: Int,
        end: Int,
        plan: PdfMetadataPlan,
    ): Boolean {
        if (end - start < 2 || bytes[start] != MARKER || bytes[start + 1] != SOI) return false
        var position = start + 2
        while (position + SEGMENT_HEADER <= end) {
            if (bytes[position] != MARKER) return false
            val marker = bytes[position + 1]
            when {
                marker == MARKER -> position++
                marker == SOS || marker == EOI -> return true
                marker == TEM || marker in RST0..RST7 -> position += 2
                else -> {
                    val length = (bytes[position + 2] shl LENGTH_SHIFT) or bytes[position + 3]
                    if (length < 2 || position + 2 + length > end) return false
                    if (isPrivate(marker)) plan.zero(bytes, position + SEGMENT_HEADER, length - 2)
                    position += 2 + length
                }
            }
        }
        return false
    }

    private fun isPrivate(marker: Int): Boolean =
        marker == APP1 || marker in APP3..APP13 || marker == APP15 || marker == COM
}
