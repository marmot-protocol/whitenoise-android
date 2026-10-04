package dev.ipf.whitenoise.android.media

// Android-free RIFF/WebP chunk walk for animation admission. It reads chunk headers,
// VP8X/ANIM/ANMF fields and the fixed VP8/VP8L/ALPH frame headers; compressed
// bitstreams are never decoded and payload bytes are never copied.

/**
 * Walks every RIFF chunk of a WebP. The RIFF size must describe the file exactly
 * and every chunk, including chunks nested in ANMF, must fit with its even padding.
 * A simple (VP8/VP8L) or extended still image is [AnimationSourceAdmission.NotAnimation]
 * only when no animation or second-image chunk is hidden anywhere in the file.
 * An animated file needs the VP8X animation flag, one ANIM before any ANMF, at least
 * one ANMF and no top-level image chunks.
 */
@Suppress("ReturnCount") // Signature, RIFF size, first chunk and dispatch each fail closed separately.
internal fun admitWebpAnimationSource(bytes: ByteArray): AnimationSourceAdmission {
    if (!isWebp(bytes)) return malformedAnimationSource
    if (u32le(bytes, RIFF_SIZE_OFFSET) + RIFF_PREAMBLE_BYTES != bytes.size.toLong()) return malformedAnimationSource
    val chunks = RiffChunkCursor(bytes, WEBP_HEADER_BYTES, bytes.size)
    if (!chunks.advance()) return malformedAnimationSource
    return when (chunks.fourCc) {
        FOURCC_VP8, FOURCC_VP8L -> admitSimpleWebp(bytes, chunks)
        FOURCC_VP8X -> admitExtendedWebp(bytes, chunks)
        else -> malformedAnimationSource
    }
}

/** A simple-format still image may be followed only by chunks that cannot add frames or images. */
private fun admitSimpleWebp(
    bytes: ByteArray,
    chunks: RiffChunkCursor,
): AnimationSourceAdmission {
    var valid = WebpBitstream.dimensions(bytes, chunks) != WebpBitstream.INVALID
    while (valid && chunks.advance()) {
        valid = !isWebpStructureChunk(chunks.fourCc)
    }
    return if (valid && !chunks.malformed) AnimationSourceAdmission.NotAnimation else malformedAnimationSource
}

/** Splits VP8X sources on the animation flag; reserved VP8X bits are ignored as specified. */
private fun admitExtendedWebp(
    bytes: ByteArray,
    chunks: RiffChunkCursor,
): AnimationSourceAdmission {
    if (chunks.payloadSize != VP8X_PAYLOAD_BYTES) return malformedAnimationSource
    val offset = chunks.payloadOffset
    val animated = u8(bytes, offset) and VP8X_ANIMATION_FLAG != 0
    val width = u24le(bytes, offset + 4) + 1
    val height = u24le(bytes, offset + 7) + 1
    return if (animated) {
        admitAnimatedWebp(bytes, chunks, width, height)
    } else if (webpStillImageIsValid(bytes, chunks, width, height, imageMustLead = false)) {
        AnimationSourceAdmission.NotAnimation
    } else {
        malformedAnimationSource
    }
}

/** Walks ANIM and every ANMF of an animated WebP while charging the canvas budget per frame. */
private fun admitAnimatedWebp(
    bytes: ByteArray,
    chunks: RiffChunkCursor,
    canvasWidth: Int,
    canvasHeight: Int,
): AnimationSourceAdmission {
    val budget = AnimationSourceBudget(canvasWidth, canvasHeight)
    budget.canvasRefusal?.let { return AnimationSourceAdmission.Refused(it) }
    var animationSeen = false
    var valid = true
    var refusal: AnimationSourceRefusal? = null
    while (valid && refusal == null && chunks.advance()) {
        val fourCc = chunks.fourCc
        valid = animatedWebpChunkIsValid(bytes, chunks, canvasWidth, canvasHeight, animationSeen, budget.frames)
        animationSeen = animationSeen || fourCc == FOURCC_ANIM
        if (valid && fourCc == FOURCC_ANMF) refusal = budget.addFrame()
    }
    return when {
        refusal != null -> AnimationSourceAdmission.Refused(refusal)
        valid && !chunks.malformed && budget.frames > 0 -> budget.admitted(AnimationSourceKind.Webp)
        else -> malformedAnimationSource
    }
}

/** Validates a top-level animation chunk without changing traversal state or charging its budget. */
private fun animatedWebpChunkIsValid(
    bytes: ByteArray,
    chunks: RiffChunkCursor,
    canvasWidth: Int,
    canvasHeight: Int,
    animationSeen: Boolean,
    frameCount: Int,
): Boolean =
    when (chunks.fourCc) {
        FOURCC_ANIM -> !animationSeen && frameCount == 0 && chunks.payloadSize == ANIM_PAYLOAD_BYTES
        FOURCC_ANMF ->
            animationSeen &&
                webpFrameIsValid(bytes, chunks.payloadOffset, chunks.payloadSize, canvasWidth, canvasHeight)
        else -> !isWebpStructureChunk(chunks.fourCc)
    }

/**
 * Validates one ANMF payload: a frame rectangle (offsets stored halved) inside the
 * canvas, then nested frame data that exactly fills the payload. Duration and the
 * blend/dispose bits carry no resource cost; the reserved bits are ignored as specified.
 */
private fun webpFrameIsValid(
    bytes: ByteArray,
    offset: Int,
    size: Int,
    canvasWidth: Int,
    canvasHeight: Int,
): Boolean {
    if (size < ANMF_HEADER_BYTES) return false
    val left = u24le(bytes, offset) * 2L
    val top = u24le(bytes, offset + 3) * 2L
    val width = u24le(bytes, offset + 6) + 1
    val height = u24le(bytes, offset + 9) + 1
    val inside = left + width <= canvasWidth && top + height <= canvasHeight
    val frameData = RiffChunkCursor(bytes, offset + ANMF_HEADER_BYTES, offset + size)
    return inside && webpStillImageIsValid(bytes, frameData, width, height, imageMustLead = true)
}

/** Consumes the remaining chunks of [chunks] as exactly one still image of [width] x [height]. */
private fun webpStillImageIsValid(
    bytes: ByteArray,
    chunks: RiffChunkCursor,
    width: Int,
    height: Int,
    imageMustLead: Boolean,
): Boolean {
    val image = WebpStillImage(width, height, imageMustLead)
    var valid = true
    while (valid && chunks.advance()) {
        valid = image.accept(bytes, chunks)
    }
    return valid && !chunks.malformed && image.complete
}

/** Chunks that add images, frames or format headers and so cannot appear as extra metadata. */
private fun isWebpStructureChunk(fourCc: Int): Boolean =
    fourCc == FOURCC_VP8X ||
        fourCc == FOURCC_ANIM ||
        fourCc == FOURCC_ANMF ||
        fourCc == FOURCC_ALPH ||
        fourCc == FOURCC_VP8 ||
        fourCc == FOURCC_VP8L

/**
 * Chunk grammar for one still image: an optional ALPH immediately followed by VP8,
 * or a VP8L alone, whose header dimensions equal the declared rectangle. Other
 * metadata chunks may surround it, except that ANMF frame data must lead with the
 * image ([imageMustLead]). Animation and VP8X chunks are never accepted.
 */
private class WebpStillImage(
    width: Int,
    height: Int,
    private val imageMustLead: Boolean,
) {
    private val expectedDimensions = packWebpDimensions(width, height)
    private val alphaRawBytes = 1L + width.toLong() * height.toLong()
    private var alphaSeen = false

    /** True after the single VP8/VP8L bitstream has been accepted. */
    var complete = false
        private set

    /** Accepts [chunk] as the next chunk of this image; false breaks the grammar. */
    fun accept(
        bytes: ByteArray,
        chunk: RiffChunkCursor,
    ): Boolean =
        when (chunk.fourCc) {
            FOURCC_ALPH -> acceptAlpha(bytes, chunk)
            FOURCC_VP8, FOURCC_VP8L -> acceptBitstream(bytes, chunk)
            else -> acceptMetadata(chunk.fourCc)
        }

    private fun acceptAlpha(
        bytes: ByteArray,
        chunk: RiffChunkCursor,
    ): Boolean {
        val accepted = !alphaSeen && !complete && alphaIsValid(bytes, chunk)
        alphaSeen = true
        return accepted
    }

    private fun acceptBitstream(
        bytes: ByteArray,
        chunk: RiffChunkCursor,
    ): Boolean {
        // VP8L carries its own alpha, so a preceding ALPH chunk is invalid for it.
        val alphaAllowed = !alphaSeen || chunk.fourCc == FOURCC_VP8
        val accepted = !complete && alphaAllowed && WebpBitstream.dimensions(bytes, chunk) == expectedDimensions
        complete = true
        return accepted
    }

    private fun acceptMetadata(fourCc: Int): Boolean {
        val positionAllowed = complete || !(alphaSeen || imageMustLead)
        return positionAllowed && !isWebpStructureChunk(fourCc)
    }

    /**
     * ALPH compression 0 stores one raw byte per pixel after a one-byte header, so the
     * payload must match the rectangle exactly. Compression 1 (lossless) has implicit
     * dimensions. Other methods are invalid; filtering, pre-processing and reserved
     * bits are left to the decoder.
     */
    private fun alphaIsValid(
        bytes: ByteArray,
        chunk: RiffChunkCursor,
    ): Boolean {
        val method =
            if (chunk.payloadSize > 0) {
                u8(bytes, chunk.payloadOffset) and ALPH_COMPRESSION_MASK
            } else {
                ALPH_COMPRESSION_UNKNOWN
            }
        return when (method) {
            ALPH_COMPRESSION_NONE -> chunk.payloadSize.toLong() == alphaRawBytes
            ALPH_COMPRESSION_LOSSLESS -> chunk.payloadSize > 1
            else -> false
        }
    }
}

/** Reads VP8 key-frame and VP8L header dimensions, mirroring libwebp's header acceptance checks. */
private object WebpBitstream {
    const val INVALID = -1L

    fun dimensions(
        bytes: ByteArray,
        chunk: RiffChunkCursor,
    ): Long =
        when (chunk.fourCc) {
            FOURCC_VP8 -> vp8(bytes, chunk.payloadOffset, chunk.payloadSize)
            FOURCC_VP8L -> vp8l(bytes, chunk.payloadOffset, chunk.payloadSize)
            else -> INVALID
        }

    /** A displayable key frame with a supported profile, a fitting first partition and a positive size. */
    private fun vp8(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        if (size < VP8_KEY_FRAME_HEADER_BYTES) return INVALID
        val frameTag = u24le(bytes, offset)
        val keyFrame = frameTag and 0x01 == 0
        val supportedProfile = (frameTag shr 1) and 0x07 <= 3
        val shown = (frameTag shr 4) and 0x01 == 1
        val partitionFits = (frameTag ushr 5) < size
        val startCode =
            u8(bytes, offset + 3) == 0x9d &&
                u8(bytes, offset + 4) == 0x01 &&
                u8(bytes, offset + 5) == 0x2a
        val width = u16le(bytes, offset + 6) and 0x3fff
        val height = u16le(bytes, offset + 8) and 0x3fff
        val valid = keyFrame && supportedProfile && shown && partitionFits && startCode && width > 0 && height > 0
        return if (valid) packWebpDimensions(width, height) else INVALID
    }

    /** A lossless header with its signature byte and version zero. */
    private fun vp8l(
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Long {
        if (size < VP8L_HEADER_BYTES) return INVALID
        val signed = u8(bytes, offset) == 0x2f
        val versionZero = u8(bytes, offset + 4) ushr 5 == 0
        val packed = u32le(bytes, offset + 1)
        val width = (packed and 0x3fffL).toInt() + 1
        val height = ((packed ushr 14) and 0x3fffL).toInt() + 1
        return if (signed && versionZero) packWebpDimensions(width, height) else INVALID
    }
}

/** Iterates RIFF chunks in `[start, end)` in place; one instance is reused for every chunk. */
private class RiffChunkCursor(
    private val bytes: ByteArray,
    start: Int,
    private val end: Int,
) {
    private var next = start

    var fourCc: Int = 0
        private set
    var payloadOffset: Int = 0
        private set
    var payloadSize: Int = 0
        private set

    /** True when the range ended inside a chunk header, payload or padding byte. */
    var malformed: Boolean = false
        private set

    /** Moves to the next chunk; false at the exact end of the range or on a malformed chunk. */
    fun advance(): Boolean {
        val remaining = (end - next).toLong()
        val hasHeader = remaining >= RIFF_CHUNK_HEADER_BYTES
        val size = if (hasHeader) u32le(bytes, next + 4) else 0L
        val paddedSize = size + (size and 1L)
        val fitsRange = hasHeader && paddedSize <= remaining - RIFF_CHUNK_HEADER_BYTES
        val hasZeroPadding =
            fitsRange && (size and 1L == 0L || u8(bytes, next + RIFF_CHUNK_HEADER_BYTES + size.toInt()) == 0)
        val fits = fitsRange && hasZeroPadding
        if (fits) {
            fourCc = u32le(bytes, next).toInt()
            payloadOffset = next + RIFF_CHUNK_HEADER_BYTES
            payloadSize = size.toInt()
            next = payloadOffset + paddedSize.toInt()
        } else if (remaining != 0L) {
            malformed = true
        }
        return fits
    }
}

/** Reads one unsigned little-endian 24-bit WebP field after the caller's bounds check. */
private fun u24le(
    bytes: ByteArray,
    offset: Int,
): Int = u16le(bytes, offset) or (u8(bytes, offset + 2) shl (2 * BITS_PER_BYTE))

private fun packWebpDimensions(
    width: Int,
    height: Int,
): Long = (width.toLong() shl Int.SIZE_BITS) or height.toLong()

/** Encodes a four-character chunk tag as the little-endian Int read by [RiffChunkCursor]. */
private fun fourCc(tag: String): Int = u32le(tag.encodeToByteArray(), 0).toInt()

private const val BITS_PER_BYTE = 8
private const val WEBP_HEADER_BYTES = 12
private const val RIFF_SIZE_OFFSET = 4
private const val RIFF_PREAMBLE_BYTES = 8L
private const val RIFF_CHUNK_HEADER_BYTES = 8
private const val VP8X_PAYLOAD_BYTES = 10
private const val VP8X_ANIMATION_FLAG = 0x02
private const val ANIM_PAYLOAD_BYTES = 6
private const val ANMF_HEADER_BYTES = 16
private const val VP8_KEY_FRAME_HEADER_BYTES = 10
private const val VP8L_HEADER_BYTES = 5
private const val ALPH_COMPRESSION_MASK = 0x03
private const val ALPH_COMPRESSION_NONE = 0
private const val ALPH_COMPRESSION_LOSSLESS = 1
private const val ALPH_COMPRESSION_UNKNOWN = -1
private val FOURCC_VP8X = fourCc("VP8X")
private val FOURCC_VP8 = fourCc("VP8 ")
private val FOURCC_VP8L = fourCc("VP8L")
private val FOURCC_ALPH = fourCc("ALPH")
private val FOURCC_ANIM = fourCc("ANIM")
private val FOURCC_ANMF = fourCc("ANMF")
