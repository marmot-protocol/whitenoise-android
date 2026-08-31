@file:Suppress(
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "MatchingDeclarationName",
    "TooManyFunctions",
)

package dev.ipf.whitenoise.android.media

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// Keep the format state machines and their byte helpers in one Android-free
// source so the JVM fuzz module exercises the exact production walkers.

/** Image containers whose metadata can be removed without decoding their pixels. */
internal enum class ImageContainerKind {
    Jpeg,
    Png,
    Webp,
    Gif,
}

/** Positively identifies the byte container accepted by the metadata walkers. */
internal fun imageContainerKind(bytes: ByteArray): ImageContainerKind? =
    when {
        isJpeg(bytes) -> ImageContainerKind.Jpeg
        isPng(bytes) -> ImageContainerKind.Png
        isWebp(bytes) -> ImageContainerKind.Webp
        isGif(bytes) -> ImageContainerKind.Gif
        else -> null
    }

/** Removes private metadata while preserving the positively identified container kind. */
internal fun stripImageContainerMetadata(bytes: ByteArray): ByteArray? =
    when (imageContainerKind(bytes)) {
        ImageContainerKind.Jpeg -> stripJpegMetadata(bytes)
        ImageContainerKind.Png -> stripPngMetadata(bytes)
        ImageContainerKind.Webp -> stripWebpMetadata(bytes)
        ImageContainerKind.Gif -> stripGifMetadata(bytes)
        null -> null
    }

/** Walks JPEG markers and drops EXIF, XMP, Photoshop/IPTC, and comment segments. */
@Suppress("ReturnCount") // Each malformed marker must fail closed at its exact boundary.
internal fun stripJpegMetadata(bytes: ByteArray): ByteArray? {
    if (!isJpeg(bytes)) return null
    val out = ByteArrayOutputStream(bytes.size)
    out.write(bytes, 0, 2)
    var pos = 2
    while (pos < bytes.size) {
        val markerStart = pos
        if (u8(bytes, pos) != 0xff) return null
        while (pos < bytes.size && u8(bytes, pos) == 0xff) pos++
        if (pos >= bytes.size) return null
        val marker = u8(bytes, pos++)
        if (marker == 0x00) return null
        if (marker == 0xd9 || marker in 0xd0..0xd7 || marker == 0x01) {
            out.write(bytes, markerStart, pos - markerStart)
            if (marker == 0xd9) return out.toByteArray()
            continue
        }
        if (pos + 2 > bytes.size) return null
        val length = u16be(bytes, pos)
        if (length < 2 || pos + length > bytes.size) return null
        val segmentEnd = pos + length
        if (marker == 0xda) {
            out.write(bytes, markerStart, segmentEnd - markerStart)
            var scanPos = segmentEnd
            var nextMarkerStart = -1
            while (scanPos < bytes.size) {
                if (u8(bytes, scanPos) != 0xff) {
                    scanPos++
                    continue
                }
                val candidateStart = scanPos
                while (scanPos < bytes.size && u8(bytes, scanPos) == 0xff) scanPos++
                if (scanPos >= bytes.size) return null
                val candidate = u8(bytes, scanPos)
                if (candidate == 0x00) {
                    scanPos++ // Stuffed 0xff byte inside entropy-coded data.
                } else if (candidate in 0xd0..0xd7) {
                    scanPos++ // Restart markers are part of the scan stream.
                } else {
                    nextMarkerStart = candidateStart
                    break
                }
            }
            if (nextMarkerStart < 0) return null
            out.write(bytes, segmentEnd, nextMarkerStart - segmentEnd)
            pos = nextMarkerStart
            continue
        }
        if (!isJpegMetadataMarker(marker)) {
            out.write(bytes, markerStart, segmentEnd - markerStart)
        }
        pos = segmentEnd
    }
    return null
}

/** Identifies JPEG marker classes that can carry private metadata. */
private fun isJpegMetadataMarker(marker: Int): Boolean =
    marker == 0xe1 ||
        // EXIF and XMP APP1 payloads.
        marker == 0xed ||
        // Photoshop/IPTC APP13 payloads.
        marker == 0xfe // User comments can carry device/location notes.

/** Walks PNG chunks and drops textual, timestamp, and EXIF metadata chunks. */
@Suppress("ReturnCount") // Each malformed chunk must fail closed before copying bytes.
internal fun stripPngMetadata(bytes: ByteArray): ByteArray? {
    if (!isPng(bytes)) return null
    val out = ByteArrayOutputStream(bytes.size)
    out.write(bytes, 0, PNG_SIGNATURE.size)
    var pos = PNG_SIGNATURE.size
    while (pos + PNG_CHUNK_OVERHEAD <= bytes.size) {
        val length = u32be(bytes, pos)
        val dataStart = pos + 8
        val chunkEndLong = dataStart.toLong() + length + 4L
        if (chunkEndLong > bytes.size.toLong()) return null
        val chunkEnd = chunkEndLong.toInt()
        val type = ascii(bytes, pos + 4, 4)
        if (!PNG_METADATA_CHUNKS.contains(type)) {
            out.write(bytes, pos, chunkEnd - pos)
        }
        pos = chunkEnd
        if (type == "IEND") return out.toByteArray()
    }
    return null
}

/** Walks RIFF chunks, drops private WebP chunks, and rewrites the bounded RIFF size. */
@Suppress("ReturnCount") // Each malformed RIFF boundary must fail closed before output.
internal fun stripWebpMetadata(bytes: ByteArray): ByteArray? {
    if (!isWebp(bytes)) return null
    val out = ByteArrayOutputStream(bytes.size)
    out.write(bytes, 0, 4) // RIFF
    writeU32le(out, 0) // patched after chunk filtering
    out.write(bytes, 8, 4) // WEBP
    var pos = 12
    while (pos + 8 <= bytes.size) {
        val chunkType = ascii(bytes, pos, 4)
        val chunkSize = u32le(bytes, pos + 4)
        val dataStart = pos + 8
        val dataEndLong = dataStart.toLong() + chunkSize
        val paddedEndLong = dataEndLong + (chunkSize and 1L)
        if (paddedEndLong > bytes.size.toLong()) return null
        val paddedEnd = paddedEndLong.toInt()
        when (chunkType) {
            "EXIF", "XMP ", "ICCP" -> Unit
            "VP8X" -> {
                val chunk = bytes.copyOfRange(pos, paddedEnd)
                if (chunkSize > 0) {
                    // Clear ICC, EXIF, and XMP presence bits after dropping those chunks.
                    chunk[8] = (chunk[8].toInt() and WEBP_FLAGS_WITHOUT_PRIVATE_METADATA).toByte()
                }
                out.write(chunk, 0, chunk.size)
            }
            else -> out.write(bytes, pos, paddedEnd - pos)
        }
        pos = paddedEnd
    }
    if (pos != bytes.size) return null
    val result = out.toByteArray()
    writeU32le(result, 4, result.size - 8)
    return result
}

/** Walks a GIF block stream while retaining only image, control, and animation-loop data. */
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal fun stripGifMetadata(bytes: ByteArray): ByteArray? {
    if (!isGif(bytes) || bytes.size < GIF_FIXED_HEADER_BYTES) return null
    val packed = u8(bytes, GIF_LOGICAL_SCREEN_PACKED_OFFSET)
    val globalColorBytes =
        if (packed and GIF_COLOR_TABLE_FLAG != 0) {
            GIF_COLOR_ENTRY_BYTES * (1 shl ((packed and GIF_COLOR_TABLE_SIZE_MASK) + 1))
        } else {
            0
        }
    var position = GIF_FIXED_HEADER_BYTES + globalColorBytes
    if (position > bytes.size) return null
    val out = ByteArrayOutputStream(bytes.size)
    out.write(bytes, 0, position)
    var imageCount = 0
    while (position < bytes.size) {
        when (u8(bytes, position)) {
            GIF_TRAILER -> {
                if (position != bytes.lastIndex || imageCount == 0) return null
                out.write(GIF_TRAILER)
                return out.toByteArray()
            }
            GIF_IMAGE_SEPARATOR -> {
                if (position + GIF_IMAGE_DESCRIPTOR_BYTES > bytes.size) return null
                val imagePacked = u8(bytes, position + GIF_IMAGE_PACKED_OFFSET)
                val localColorBytes =
                    if (imagePacked and GIF_COLOR_TABLE_FLAG != 0) {
                        GIF_COLOR_ENTRY_BYTES * (1 shl ((imagePacked and GIF_COLOR_TABLE_SIZE_MASK) + 1))
                    } else {
                        0
                    }
                val imageDataStart = position + GIF_IMAGE_DESCRIPTOR_BYTES + localColorBytes
                if (imageDataStart >= bytes.size) return null
                val imageEnd = gifSubBlocksEnd(bytes, imageDataStart + 1) ?: return null
                out.write(bytes, position, imageEnd - position)
                position = imageEnd
                imageCount++
            }
            GIF_EXTENSION_INTRODUCER -> {
                if (position + 2 > bytes.size) return null
                val label = u8(bytes, position + 1)
                val extensionEnd = gifSubBlocksEnd(bytes, position + 2) ?: return null
                val keep =
                    when (label) {
                        GIF_GRAPHICS_CONTROL_LABEL -> true
                        GIF_APPLICATION_LABEL -> isGifLoopApplicationExtension(bytes, position, extensionEnd)
                        GIF_COMMENT_LABEL,
                        GIF_PLAIN_TEXT_LABEL,
                        -> false
                        else -> return null
                    }
                if (keep) out.write(bytes, position, extensionEnd - position)
                position = extensionEnd
            }
            else -> return null
        }
    }
    return null
}

/** Returns the byte after a terminated GIF sub-block chain, or null when truncated. */
@Suppress("ReturnCount")
private fun gifSubBlocksEnd(
    bytes: ByteArray,
    start: Int,
): Int? {
    var position = start
    while (position < bytes.size) {
        val blockSize = u8(bytes, position)
        position++
        if (blockSize == 0) return position
        if (position + blockSize > bytes.size) return null
        position += blockSize
    }
    return null
}

/** Allows only the two standard GIF application identifiers used for animation loops. */
@Suppress("ReturnCount")
private fun isGifLoopApplicationExtension(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Boolean {
    val blockSizeOffset = start + 2
    if (blockSizeOffset >= end || u8(bytes, blockSizeOffset) != GIF_APPLICATION_IDENTIFIER_BYTES) return false
    val identifierStart = blockSizeOffset + 1
    if (identifierStart + GIF_APPLICATION_IDENTIFIER_BYTES > end) return false
    val identifier = ascii(bytes, identifierStart, GIF_APPLICATION_IDENTIFIER_BYTES)
    return identifier == "NETSCAPE2.0" || identifier == "ANIMEXTS1.0"
}

/** Tests the JPEG start-of-image signature without reading outside [bytes]. */
internal fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= 2 && u8(bytes, 0) == 0xff && u8(bytes, 1) == 0xd8

/** Tests the complete PNG signature without reading outside [bytes]. */
internal fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= PNG_SIGNATURE.size &&
        PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

/** Tests the RIFF and WEBP signatures without trusting the declared RIFF size. */
internal fun isWebp(bytes: ByteArray): Boolean =
    bytes.size >= 12 &&
        asciiEquals(bytes, 0, "RIFF") &&
        asciiEquals(bytes, 8, "WEBP")

/** Tests either complete GIF version signature without reading outside [bytes]. */
internal fun isGif(bytes: ByteArray): Boolean =
    bytes.size >= 6 &&
        (asciiEquals(bytes, 0, "GIF87a") || asciiEquals(bytes, 0, "GIF89a"))

/** Reads one unsigned byte after the caller has established the offset bound. */
internal fun u8(
    bytes: ByteArray,
    offset: Int,
): Int = bytes[offset].toInt() and 0xff

/** Reads one unsigned big-endian 16-bit value after the caller's bounds check. */
internal fun u16be(
    bytes: ByteArray,
    offset: Int,
): Int = (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

/** Reads one unsigned little-endian 16-bit value after the caller's bounds check. */
internal fun u16le(
    bytes: ByteArray,
    offset: Int,
): Int = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

/** Reads one unsigned big-endian 32-bit value into a non-negative [Long]. */
internal fun u32be(
    bytes: ByteArray,
    offset: Int,
): Long =
    (u8(bytes, offset).toLong() shl 24) or
        (u8(bytes, offset + 1).toLong() shl 16) or
        (u8(bytes, offset + 2).toLong() shl 8) or
        u8(bytes, offset + 3).toLong()

/** Reads one unsigned little-endian 32-bit value into a non-negative [Long]. */
internal fun u32le(
    bytes: ByteArray,
    offset: Int,
): Long =
    u8(bytes, offset).toLong() or
        (u8(bytes, offset + 1).toLong() shl 8) or
        (u8(bytes, offset + 2).toLong() shl 16) or
        (u8(bytes, offset + 3).toLong() shl 24)

/** Decodes a bounded ASCII field after its container walker has checked the range. */
internal fun ascii(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String = String(bytes, offset, length, StandardCharsets.US_ASCII)

/** Compares a bounded ASCII field without slicing the input byte array. */
internal fun asciiEquals(
    bytes: ByteArray,
    offset: Int,
    value: String,
): Boolean = offset + value.length <= bytes.size && ascii(bytes, offset, value.length) == value

/** Writes a 32-bit little-endian value into a growing output container. */
internal fun writeU32le(
    out: ByteArrayOutputStream,
    value: Int,
) {
    out.write(value and 0xff)
    out.write((value ushr 8) and 0xff)
    out.write((value ushr 16) and 0xff)
    out.write((value ushr 24) and 0xff)
}

/** Patches a 32-bit little-endian value into an already bounded byte array. */
internal fun writeU32le(
    bytes: ByteArray,
    offset: Int,
    value: Int,
) {
    bytes[offset] = (value and 0xff).toByte()
    bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
    bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

internal val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
internal const val PNG_CHUNK_HEADER_BYTES = 8
internal const val PNG_CHUNK_OVERHEAD = 12
internal const val PNG_CHUNK_TYPE_OFFSET = 4
internal const val PNG_CHUNK_TYPE_BYTES = 4

private val PNG_METADATA_CHUNKS = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")
private const val GIF_FIXED_HEADER_BYTES = 13
private const val GIF_LOGICAL_SCREEN_PACKED_OFFSET = 10
private const val GIF_COLOR_TABLE_FLAG = 0x80
private const val GIF_COLOR_TABLE_SIZE_MASK = 0x07
private const val GIF_COLOR_ENTRY_BYTES = 3
private const val GIF_TRAILER = 0x3b
private const val GIF_IMAGE_SEPARATOR = 0x2c
private const val GIF_IMAGE_DESCRIPTOR_BYTES = 10
private const val GIF_IMAGE_PACKED_OFFSET = 9
private const val GIF_EXTENSION_INTRODUCER = 0x21
private const val GIF_GRAPHICS_CONTROL_LABEL = 0xf9
private const val GIF_APPLICATION_LABEL = 0xff
private const val GIF_COMMENT_LABEL = 0xfe
private const val GIF_PLAIN_TEXT_LABEL = 0x01
private const val GIF_APPLICATION_IDENTIFIER_BYTES = 11
private const val WEBP_FLAGS_WITHOUT_PRIVATE_METADATA = 0xd3
