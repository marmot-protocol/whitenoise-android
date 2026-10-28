package dev.ipf.whitenoise.android.media

/** What a file's content says about video, independent of the MIME type its provider reports (#3376). */
internal enum class VideoContent {
    /** ISO BMFF or Matroska with at least one video track, so its metadata must be removed whatever its type. */
    VideoContainer,

    /**
     * ISO BMFF or Matroska without a video track the walk could find, for example audio-only MP4 or WebM, or a file
     * whose structure the walk refused before it reached a video track.
     */
    ContainerWithoutVideo,

    /** A video container the scrubber cannot clean: AVI, MPEG-TS or M2TS, MPEG-PS, ASF, FLV, Ogg, RealMedia or MXF. */
    UnsupportedContainer,

    /** Anything else, such as text or an archive. */
    Other,
}

/**
 * Leading signatures of the video containers the scrubber recognises but cannot clean. Each check needs every byte
 * it compares, so a shorter input never matches.
 */
internal object VideoContainerSignatures {
    private const val TS_PACKET = 188L
    private const val TS_SYNC = 0x47

    // M2TS prefixes each 188-byte packet with a 4-byte timestamp.
    private const val M2TS_PREFIX = 4L
    private const val M2TS_PACKET = TS_PACKET + M2TS_PREFIX
    private const val TS_PACKETS_CHECKED = 3
    private const val BYTE_MASK = 0xFF

    private val RIFF = ascii("RIFF")
    private val AVI = ascii("AVI ")
    private const val AVI_FORM_OFFSET = 8L
    private val PROGRAM_STREAM = bytes(0x00, 0x00, 0x01, 0xBA)
    private val ASF =
        bytes(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11, 0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C)
    private val FLV = bytes('F'.code, 'L'.code, 'V'.code, 0x01)
    private val OGG = ascii("OggS")
    private val REAL_MEDIA = ascii(".RMF")
    private val MXF = bytes(0x06, 0x0E, 0x2B, 0x34)
    private val LEADING = listOf(PROGRAM_STREAM, ASF, FLV, OGG, REAL_MEDIA, MXF)

    /** Whether [reader] starts like one of the recognised video containers the scrubber cannot clean. */
    fun matches(reader: ScrubReader): Boolean =
        LEADING.any { reader.startsWith(0L, it) } ||
            (reader.startsWith(0L, RIFF) && reader.startsWith(AVI_FORM_OFFSET, AVI)) ||
            reader.hasTransportSync(0L, TS_PACKET) ||
            reader.hasTransportSync(M2TS_PREFIX, M2TS_PACKET)

    /** Whether a sync byte sits at [first] and at the same place in the next two packets of [packetSize] bytes. */
    private fun ScrubReader.hasTransportSync(
        first: Long,
        packetSize: Long,
    ): Boolean =
        (0 until TS_PACKETS_CHECKED).all { packet ->
            val position = first + packet * packetSize
            position < size && u8(position) == TS_SYNC
        }

    /** Whether the bytes at [position] equal [expected]; false when the input ends first. */
    private fun ScrubReader.startsWith(
        position: Long,
        expected: ByteArray,
    ): Boolean =
        position + expected.size <= size &&
            expected.indices.all { index -> u8(position + index) == expected[index].toInt() and BYTE_MASK }

    /** [text] as ASCII bytes. */
    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    /** [values] as bytes. */
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
