package dev.ipf.whitenoise.android.media

/** What a file's content says about video, independent of the MIME type its provider reports (#3376). */
internal enum class VideoContent {
    /**
     * Content alone proves a video, so its metadata must be removed whatever its reported type: Matroska or WebM with
     * a video track, or ISO BMFF with a video brand (or no `ftyp`, as old QuickTime) and a video track.
     */
    VideoContainer,

    /**
     * ISO BMFF or Matroska that content alone does not prove is a video: no video track in a complete track list
     * (audio-only MP4 or WebM, even with a trailer such as an ID3 tag after the movie), a still-image brand (a Canon
     * CR3 raw photo, HEIF or AVIF) or an unrecognised brand read without a malformed structure, or no movie at all
     * (a HEIF still). Only a reported video type makes it one.
     */
    UnconfirmedContainer,

    /**
     * A recognised ISO BMFF or Matroska file, whose first box or element parsed, that the walk refused as malformed
     * or before it could read the track list, unless it had already read a complete track list without a video track.
     * Its content proves nothing either way, so it is never sent unchanged.
     */
    UnreadableContainer,

    /**
     * A video container the scrubber cannot clean: AVI, MPEG-TS or M2TS, MPEG-PS, ASF, FLV, Ogg, RealMedia, MXF or
     * DV.
     */
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

    // The DV DIF header block, probed the way ffmpeg does: the first word with its one variable bit masked off.
    private const val DV_MASK = 0xFFFFFF7FL
    private const val DV_HEADER = 0x1F07003FL

    /** Whether [reader] starts like one of the recognised video containers the scrubber cannot clean. */
    fun matches(reader: ScrubReader): Boolean {
        val leading = LEADING.any { reader.startsWith(0L, it) } || reader.isAvi() || reader.isDigitalVideo()
        return leading || reader.hasTransportSync(0L, TS_PACKET) || reader.hasTransportSync(M2TS_PREFIX, M2TS_PACKET)
    }

    /** Whether this is a RIFF file of the AVI form. */
    private fun ScrubReader.isAvi(): Boolean = startsWith(0L, RIFF) && startsWith(AVI_FORM_OFFSET, AVI)

    /** Whether this starts with a DV DIF header block. */
    private fun ScrubReader.isDigitalVideo(): Boolean = size >= Int.SIZE_BYTES && u32(0L) and DV_MASK == DV_HEADER

    /** Whether a sync byte sits at [first] and at the same place in the next two packets of [packetSize] bytes. */
    private fun ScrubReader.hasTransportSync(
        first: Long,
        packetSize: Long,
    ): Boolean =
        (0 until TS_PACKETS_CHECKED).all { packet ->
            val position = first + packet * packetSize
            position < size && u8(position) == TS_SYNC
        }

    /** Whether the bytes at [position] equal [expected], false when the input ends first. */
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

/** What an ISO BMFF file's `ftyp` says it is. Matroska and WebM always count as [Video]. */
internal enum class IsoBrand {
    /** A known video brand, or no `ftyp` at all, as in QuickTime written before `ftyp` existed. */
    Video,

    /** A still-image or image-sequence brand, as a major or compatible brand. */
    StillImage,

    /** Any other brand, or an `ftyp` that does not fit the file. */
    Unknown,
}

/**
 * The `ftyp` brands that decide whether an ISO BMFF file's content alone may mark it as a video. Some still-image
 * formats are ISO BMFF with tracks that use the video handler: a Canon CR3 raw photo keeps its image tracks that way,
 * and HEIF and AVIF image sequences can too. Content must never override a non-video report for them.
 */
internal object IsoBrands {
    private const val FTYP_MAJOR_OFFSET = 8L
    private const val FTYP_COMPATIBLE_OFFSET = 16L
    private const val BRAND_BYTES = 4L
    private const val MAX_BRANDS = 64

    // Major brands written by cameras, phones and video tools. Anything else is not trusted as video by content.
    private val VIDEO =
        setOf(
            "isom",
            "iso2",
            "iso3",
            "iso4",
            "iso5",
            "iso6",
            "iso7",
            "iso8",
            "iso9",
            "mp41",
            "mp42",
            "mp71",
            "avc1",
            "M4V ",
            "M4VH",
            "M4VP",
            "qt  ",
            "MSNV",
            "dash",
            "f4v ",
            "mmp4",
            "dby1",
            "NDAS",
            "NDSC",
            "NDSH",
            "NDSM",
            "NDSP",
            "NDSS",
            "NDXC",
            "NDXH",
            "NDXM",
            "NDXP",
            "NDXS",
            "XAVC",
        )

    // 3GPP and 3GPP2 brand families end in a version character.
    private val VIDEO_PREFIXES = listOf("3gp", "3g2", "3gs")

    // Still-image and image-sequence brands. Any of them, major or compatible, rules a file out.
    private val STILL_IMAGE =
        setOf(
            "crx ",
            "heic",
            "heix",
            "heim",
            "heis",
            "hevc",
            "hevx",
            "hevm",
            "hevs",
            "mif1",
            "mif2",
            "msf1",
            "miaf",
            "avif",
            "avis",
            "avio",
            "jpeg",
            "jpgs",
            "avci",
            "avcs",
            "vvic",
            "vvis",
        )

    /**
     * Classifies the brands: no `ftyp` at all is [IsoBrand.Video] (QuickTime written before `ftyp` existed), any major
     * or compatible still-image brand is [IsoBrand.StillImage], a known video major brand is [IsoBrand.Video], and
     * anything else, including an `ftyp` that does not fit the file, is [IsoBrand.Unknown].
     */
    fun classify(reader: ScrubReader): IsoBrand {
        val size = reader.u32(0L)
        val hasFtyp = reader.u32(Int.SIZE_BYTES.toLong()).toInt() == IsoBoxTypes.FTYP
        val fits = size >= FTYP_COMPATIBLE_OFFSET && size <= reader.size
        return when {
            !hasFtyp -> IsoBrand.Video
            !fits -> IsoBrand.Unknown
            else -> {
                val major = reader.brand(FTYP_MAJOR_OFFSET)
                val compatible =
                    (FTYP_COMPATIBLE_OFFSET until size step BRAND_BYTES)
                        .take(MAX_BRANDS)
                        .filter { it + BRAND_BYTES <= size }
                        .map { reader.brand(it) }
                when {
                    (compatible + major).any { it in STILL_IMAGE } -> IsoBrand.StillImage
                    isVideoBrand(major) -> IsoBrand.Video
                    else -> IsoBrand.Unknown
                }
            }
        }
    }

    /** Whether [brand] is a known video major brand. */
    private fun isVideoBrand(brand: String): Boolean = brand in VIDEO || VIDEO_PREFIXES.any { brand.startsWith(it) }

    /** The four-character brand at [position]. */
    private fun ScrubReader.brand(position: Long): String {
        val chars = CharArray(Int.SIZE_BYTES) { index -> u8(position + index).toChar() }
        return String(chars)
    }
}
