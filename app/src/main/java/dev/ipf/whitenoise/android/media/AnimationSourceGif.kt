package dev.ipf.whitenoise.android.media

// Android-free GIF block walk for animation admission. It reads only block headers,
// colour-table sizes and sub-block lengths; LZW pixel data is skipped, never decoded.

/**
 * Walks the logical screen, colour tables, image descriptors, every extension and
 * the trailer of a GIF. Each frame must have a positive rectangle inside the
 * logical screen, the trailer must be the final byte, and at least one frame must
 * exist. Graphic-control, application and plain-text extensions must start with
 * their specified block sizes; plain-text and unknown extensions are walked as
 * bounded metadata.
 */
@Suppress("ReturnCount") // The block state machine fails closed at each exact block boundary.
internal fun admitGifAnimationSource(bytes: ByteArray): AnimationSourceAdmission {
    if (!hasAnimationSourceRange(bytes, 0L, GIF_SCREEN_HEADER_BYTES.toLong())) return malformedAnimationSource
    val width = u16le(bytes, GIF_SCREEN_WIDTH_OFFSET)
    val height = u16le(bytes, GIF_SCREEN_HEIGHT_OFFSET)
    val budget = AnimationSourceBudget(width, height)
    budget.canvasRefusal?.let { return AnimationSourceAdmission.Refused(it) }
    var cursor = GIF_SCREEN_HEADER_BYTES.toLong() + gifColorTableBytes(u8(bytes, GIF_SCREEN_PACKED_OFFSET))
    while (cursor < bytes.size.toLong()) {
        val position = cursor.toInt()
        cursor =
            when (u8(bytes, position)) {
                GIF_BLOCK_TRAILER -> return gifTrailerAdmission(bytes, position, budget)
                GIF_BLOCK_IMAGE -> {
                    budget.addFrame()?.let { return AnimationSourceAdmission.Refused(it) }
                    gifImageEnd(bytes, position, width, height)
                }
                GIF_BLOCK_EXTENSION -> gifExtensionEnd(bytes, position)
                else -> GIF_INVALID
            }
        if (cursor == GIF_INVALID) return malformedAnimationSource
    }
    return malformedAnimationSource // Truncated before the trailer.
}

/** Admits only a trailer that is the final byte of a GIF holding at least one frame. */
private fun gifTrailerAdmission(
    bytes: ByteArray,
    position: Int,
    budget: AnimationSourceBudget,
): AnimationSourceAdmission =
    if (position == bytes.lastIndex && budget.frames > 0) {
        budget.admitted(AnimationSourceKind.Gif)
    } else {
        malformedAnimationSource
    }

/** Returns the offset after one complete image block, or [GIF_INVALID]. */
@Suppress("ReturnCount") // Descriptor, rectangle and LZW header each fail closed separately.
private fun gifImageEnd(
    bytes: ByteArray,
    start: Int,
    canvasWidth: Int,
    canvasHeight: Int,
): Long {
    if (!hasAnimationSourceRange(bytes, start.toLong(), GIF_IMAGE_DESCRIPTOR_LENGTH)) return GIF_INVALID
    val left = u16le(bytes, start + 1)
    val top = u16le(bytes, start + 3)
    val width = u16le(bytes, start + 5)
    val height = u16le(bytes, start + 7)
    val inside = width > 0 && height > 0 && left + width <= canvasWidth && top + height <= canvasHeight
    val lzwOffset = start.toLong() + GIF_IMAGE_DESCRIPTOR_LENGTH + gifColorTableBytes(u8(bytes, start + 9))
    if (!inside || !hasAnimationSourceRange(bytes, lzwOffset, 1L)) return GIF_INVALID
    val minimumCodeSize = u8(bytes, lzwOffset.toInt())
    if (minimumCodeSize !in GIF_MIN_LZW_CODE_SIZE..GIF_MAX_LZW_CODE_SIZE) return GIF_INVALID
    return gifSubBlockChainEnd(bytes, lzwOffset + 1L)
}

/** Returns the offset after one complete extension block, or [GIF_INVALID]. */
private fun gifExtensionEnd(
    bytes: ByteArray,
    start: Int,
): Long {
    // Label byte plus the first sub-block length must both be present.
    if (!hasAnimationSourceRange(bytes, start.toLong() + 1L, 2L)) return GIF_INVALID
    val firstBlock = start + 2
    val requiredFirstBlockSize =
        when (u8(bytes, start + 1)) {
            GIF_LABEL_GRAPHIC_CONTROL -> GIF_GRAPHIC_CONTROL_BLOCK_SIZE
            GIF_LABEL_APPLICATION -> GIF_APPLICATION_BLOCK_SIZE
            GIF_LABEL_PLAIN_TEXT -> GIF_PLAIN_TEXT_BLOCK_SIZE
            else -> GIF_ANY_BLOCK_SIZE
        }
    val firstBlockMatches =
        requiredFirstBlockSize == GIF_ANY_BLOCK_SIZE || u8(bytes, firstBlock) == requiredFirstBlockSize
    val graphicControlTerminator = firstBlock.toLong() + 1L + GIF_GRAPHIC_CONTROL_BLOCK_SIZE
    return when {
        !firstBlockMatches -> GIF_INVALID
        requiredFirstBlockSize == GIF_GRAPHIC_CONTROL_BLOCK_SIZE ->
            if (
                hasAnimationSourceRange(bytes, graphicControlTerminator, 1L) &&
                u8(bytes, graphicControlTerminator.toInt()) == 0
            ) {
                graphicControlTerminator + 1L
            } else {
                GIF_INVALID
            }
        else -> gifSubBlockChainEnd(bytes, firstBlock.toLong())
    }
}

/** Returns the offset after a zero-terminated sub-block chain, or [GIF_INVALID] when truncated. */
private fun gifSubBlockChainEnd(
    bytes: ByteArray,
    start: Long,
): Long {
    var cursor = start
    var end = GIF_INVALID
    while (end == GIF_INVALID && cursor < bytes.size.toLong()) {
        val blockSize = u8(bytes, cursor.toInt())
        cursor += 1L + blockSize
        if (blockSize == 0) end = cursor
    }
    return end
}

/** Bytes occupied by a global or local colour table described by [packed]. */
private fun gifColorTableBytes(packed: Int): Int =
    if (packed and GIF_COLOR_TABLE_PRESENT != 0) {
        GIF_COLOR_BYTES_PER_ENTRY * (1 shl ((packed and GIF_COLOR_TABLE_SIZE_BITS) + 1))
    } else {
        0
    }

private const val GIF_INVALID = -1L
private const val GIF_SCREEN_HEADER_BYTES = 13
private const val GIF_SCREEN_WIDTH_OFFSET = 6
private const val GIF_SCREEN_HEIGHT_OFFSET = 8
private const val GIF_SCREEN_PACKED_OFFSET = 10
private const val GIF_IMAGE_DESCRIPTOR_LENGTH = 10L
private const val GIF_COLOR_TABLE_PRESENT = 0x80
private const val GIF_COLOR_TABLE_SIZE_BITS = 0x07
private const val GIF_COLOR_BYTES_PER_ENTRY = 3
private const val GIF_BLOCK_TRAILER = 0x3b
private const val GIF_BLOCK_IMAGE = 0x2c
private const val GIF_BLOCK_EXTENSION = 0x21
private const val GIF_LABEL_GRAPHIC_CONTROL = 0xf9
private const val GIF_LABEL_APPLICATION = 0xff
private const val GIF_LABEL_PLAIN_TEXT = 0x01
private const val GIF_GRAPHIC_CONTROL_BLOCK_SIZE = 4
private const val GIF_APPLICATION_BLOCK_SIZE = 11
private const val GIF_PLAIN_TEXT_BLOCK_SIZE = 12
private const val GIF_ANY_BLOCK_SIZE = -1
private const val GIF_MIN_LZW_CODE_SIZE = 2
private const val GIF_MAX_LZW_CODE_SIZE = 8
