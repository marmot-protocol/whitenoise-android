package dev.ipf.whitenoise.android.media

import java.io.IOException
import java.util.Locale

/**
 * What the headers of a container the scrubber cannot clean say it carries (#3376). Only proven audio may be sent
 * unchanged under a non-video type, so anything the bounded read cannot settle counts as undetermined, never as audio.
 */
internal enum class CarriedStreams(
    val content: VideoContent,
) {
    /** At least one video stream. */
    Video(VideoContent.UnsupportedVideo),

    /** Audio streams only, apart from streams that carry no media, such as an Ogg Skeleton. */
    AudioOnly(VideoContent.UnsupportedAudio),

    /** No stream list could be read in full within the bounded read, or a stream is of an unknown kind. */
    Undetermined(VideoContent.UnsupportedUndetermined),
}

/** What one declared stream carries. */
internal enum class StreamKind {
    Video,
    Audio,

    /** A stream that carries no media, such as an Ogg Skeleton or ASF script commands. */
    Neutral,
    Unknown,
}

/** Any video stream makes a video, any unknown stream leaves it undetermined, and audio needs at least one stream. */
internal fun carriedStreams(kinds: Collection<StreamKind>): CarriedStreams =
    when {
        StreamKind.Video in kinds -> CarriedStreams.Video
        StreamKind.Unknown in kinds -> CarriedStreams.Undetermined
        StreamKind.Audio in kinds -> CarriedStreams.AudioOnly
        else -> CarriedStreams.Undetermined
    }

/** Runs one bounded header [probe], where a read past the end of the input means the streams are undetermined. */
internal inline fun probeStreams(probe: () -> CarriedStreams): CarriedStreams =
    try {
        probe()
    } catch (_: IOException) {
        CarriedStreams.Undetermined
    }

/**
 * FLV: the header's flags declare video and audio. A video flag is a video. An audio-only flag is trusted only when
 * the first tags, read one bounded header at a time, hold no video tag and end cleanly.
 */
internal object FlvStreams {
    private const val HEADER_BYTES = 9L
    private const val FLAGS_OFFSET = 4L
    private const val DATA_OFFSET_OFFSET = 5L
    private const val VIDEO_FLAG = 0x01
    private const val AUDIO_FLAG = 0x04
    private const val PREVIOUS_TAG_SIZE_BYTES = 4L
    private const val TAG_HEADER_BYTES = 11L
    private const val TAG_SIZE_BYTES = 3
    private const val TAG_TYPE_MASK = 0x1F
    private const val VIDEO_TAG = 9
    private const val MAX_TAGS = 64

    /** What the FLV in [reader] carries, from its header flags and first tags. */
    fun probe(reader: ScrubReader): CarriedStreams {
        val flags = if (reader.size >= HEADER_BYTES) reader.u8(FLAGS_OFFSET) else 0
        return when {
            flags and VIDEO_FLAG != 0 -> CarriedStreams.Video
            flags and AUDIO_FLAG == 0 -> CarriedStreams.Undetermined
            else -> audioTags(reader)
        }
    }

    /**
     * Reads up to [MAX_TAGS] tag headers after the header: a video tag is a video, and audio is proven only when every
     * tag read fits the file and the read stops at its end or at the tag limit.
     */
    private fun audioTags(reader: ScrubReader): CarriedStreams {
        val dataOffset = reader.u32(DATA_OFFSET_OFFSET)
        var position = dataOffset + PREVIOUS_TAG_SIZE_BYTES
        var tags = 0
        var video = false
        while (!video && tags < MAX_TAGS && position + TAG_HEADER_BYTES <= reader.size) {
            video = reader.u8(position) and TAG_TYPE_MASK == VIDEO_TAG
            position += TAG_HEADER_BYTES + reader.unsigned(position + 1, TAG_SIZE_BYTES) + PREVIOUS_TAG_SIZE_BYTES
            tags++
        }
        val clean = dataOffset >= HEADER_BYTES && (position == reader.size || tags == MAX_TAGS)
        return when {
            video -> CarriedStreams.Video
            clean -> CarriedStreams.AudioOnly
            else -> CarriedStreams.Undetermined
        }
    }
}

/**
 * Ogg: every multiplexed stream starts with a beginning-of-stream page, and those pages come first, so the first
 * packet of each names its codec. Reading stops at the first other page, or undetermined at a broken or missing one.
 */
internal object OggStreams {
    private const val PAGE_HEADER_BYTES = 27L
    private const val VERSION_OFFSET = 4L
    private const val HEADER_TYPE_OFFSET = 5L
    private const val SEGMENT_COUNT_OFFSET = 26L
    private const val BEGINNING_OF_STREAM = 0x02
    private const val IDENTIFIER_BYTES = 8L
    private const val MAX_PAGES = 64
    private val CAPTURE = ascii("OggS")
    private val VIDEO =
        listOf(
            byteArrayOf(0x80.toByte()) + ascii("theora"),
            byteArrayOf(0x80.toByte()) + ascii("daala"),
            ascii("BBCD"),
            ascii("KW-DIRAC"),
            byteArrayOf(1) + ascii("video"),
        )
    private val AUDIO =
        listOf(
            byteArrayOf(1) + ascii("vorbis"),
            ascii("OpusHead"),
            byteArrayOf(0x7F) + ascii("FLAC"),
            ascii("Speex   "),
            ascii("CELT    "),
        )
    private val NEUTRAL = listOf(ascii("fishead") + byteArrayOf(0))

    /** One page: whether it begins a stream, and where its payload starts and ends. */
    private class Page(
        val beginsStream: Boolean,
        val payloadStart: Long,
        val end: Long,
    )

    /** What the Ogg in [reader] carries, from the codecs its beginning-of-stream pages name. */
    fun probe(reader: ScrubReader): CarriedStreams =
        beginningPages(reader)?.let { pages -> carriedStreams(pages.map { codec(reader, it) }) }
            ?: CarriedStreams.Undetermined

    /**
     * The beginning-of-stream pages from the start of the file, or null when a page is broken or there are more than
     * [MAX_PAGES]. The list ends at the first other page, or where the file ends cleanly between pages.
     */
    private fun beginningPages(reader: ScrubReader): List<Page>? {
        val pages = mutableListOf<Page>()
        var position = 0L
        var complete: Boolean? = null
        while (complete == null) {
            val page = if (position < reader.size) readPage(reader, position) else null
            complete =
                when {
                    position == reader.size -> true
                    page == null -> false
                    !page.beginsStream -> true
                    pages.size == MAX_PAGES -> false
                    else -> {
                        pages += page
                        position = page.end
                        null
                    }
                }
        }
        val done = complete == true
        return pages.takeIf { done && it.isNotEmpty() }
    }

    /** The page at [position], or null when its header or payload does not fit the file. */
    private fun readPage(
        reader: ScrubReader,
        position: Long,
    ): Page? {
        val headerEnd = position + PAGE_HEADER_BYTES
        val valid =
            headerEnd <= reader.size && reader.matches(position, CAPTURE) && reader.u8(position + VERSION_OFFSET) == 0
        val segments = if (valid) reader.u8(position + SEGMENT_COUNT_OFFSET) else 0
        val payloadStart = headerEnd + segments
        val laced = valid && payloadStart <= reader.size
        val lacing = ByteArray(if (laced) segments else 0)
        if (laced) reader.store.read(headerEnd, lacing, 0, lacing.size)
        val end = payloadStart + lacing.sumOf { it.toLong() and BYTE_MASK }
        val beginsStream = laced && reader.u8(position + HEADER_TYPE_OFFSET) and BEGINNING_OF_STREAM != 0
        return if (laced && end <= reader.size) Page(beginsStream, payloadStart, end) else null
    }

    /** The kind of stream whose first packet starts [page]'s payload. */
    private fun codec(
        reader: ScrubReader,
        page: Page,
    ): StreamKind {
        val head = ByteArray(minOf(IDENTIFIER_BYTES, page.end - page.payloadStart).toInt())
        reader.store.read(page.payloadStart, head, 0, head.size)
        return when {
            VIDEO.any(head::startsWithBytes) -> StreamKind.Video
            AUDIO.any(head::startsWithBytes) -> StreamKind.Audio
            NEUTRAL.any(head::startsWithBytes) -> StreamKind.Neutral
            else -> StreamKind.Unknown
        }
    }
}

/**
 * ASF (WMV and WMA): the Header Object lists a Stream Properties Object per stream, whose stream type GUID says audio
 * or video. Streams declared inside the Header Extension Object are found by scanning it, up to a fixed size. Only
 * object headers are read, so a large header, such as one holding cover art, costs no more than a small one.
 */
internal object AsfStreams {
    private const val GUID_BYTES = 16
    private const val SIZE_BYTES = 8
    private const val COUNT_BYTES = 4
    private const val HEADER_OBJECT_BYTES = 30L
    private const val OBJECT_HEADER_BYTES = 24L
    private const val STREAM_PROPERTIES_MIN_BYTES = 40L
    private const val MAX_OBJECTS = 256L
    private const val MAX_EXTENSION_BYTES = 64L * 1024L
    private val STREAM_PROPERTIES = asfGuid("B7DC0791-A9B7-11CF-8EE6-00C00C205365")
    private val HEADER_EXTENSION = asfGuid("5FBF03B5-A92E-11CF-8EE3-00C00C205365")
    private val AUDIO_MEDIA = asfGuid("F8699E40-5B4D-11CF-A8FD-00805F5C442B")
    private val VIDEO_MEDIA = asfGuid("BC19EFC0-5B4D-11CF-A8FD-00805F5C442B")
    private val COMMAND_MEDIA = asfGuid("59DACFC0-59E6-11D0-A3AC-00A0C90348F6")

    /** What the ASF in [reader] carries, from the stream types its Header Object declares. */
    fun probe(reader: ScrubReader): CarriedStreams {
        val sized = reader.size >= HEADER_OBJECT_BYTES
        val headerEnd = if (sized) reader.littleEndian(GUID_BYTES.toLong(), SIZE_BYTES) else 0L
        val count = if (sized) reader.littleEndian(OBJECT_HEADER_BYTES, COUNT_BYTES) else 0L
        val readable = headerEnd in HEADER_OBJECT_BYTES..reader.size && count in 1..MAX_OBJECTS
        return (if (readable) headerStreams(reader, headerEnd, count) else null)?.let(::carriedStreams)
            ?: CarriedStreams.Undetermined
    }

    /** The kinds of every stream the header declares, or null when an object does not fit the header. */
    private fun headerStreams(
        reader: ScrubReader,
        headerEnd: Long,
        count: Long,
    ): List<StreamKind>? {
        val kinds = mutableListOf<StreamKind>()
        var position = HEADER_OBJECT_BYTES
        var index = 0L
        var intact = true
        while (intact && index < count) {
            val fits = position + OBJECT_HEADER_BYTES <= headerEnd
            val size = if (fits) reader.littleEndian(position + GUID_BYTES, SIZE_BYTES) else 0L
            intact = size in OBJECT_HEADER_BYTES..(headerEnd - position)
            if (intact) {
                val guid = reader.bytesAt(position, GUID_BYTES)
                when {
                    guid.contentEquals(STREAM_PROPERTIES) -> kinds += streamKind(reader, position, size)
                    guid.contentEquals(HEADER_EXTENSION) -> kinds += extensionStreams(reader, position, size)
                }
                position += size
                index++
            }
        }
        return kinds.takeIf { intact }
    }

    /** The stream type of the Stream Properties Object of [size] bytes at [position]. */
    private fun streamKind(
        reader: ScrubReader,
        position: Long,
        size: Long,
    ): StreamKind =
        if (size < STREAM_PROPERTIES_MIN_BYTES) {
            StreamKind.Unknown
        } else {
            kindOf(reader.bytesAt(position + OBJECT_HEADER_BYTES, GUID_BYTES))
        }

    /** The stream types of every Stream Properties Object embedded in the Header Extension Object at [position]. */
    private fun extensionStreams(
        reader: ScrubReader,
        position: Long,
        size: Long,
    ): List<StreamKind> {
        if (size > MAX_EXTENSION_BYTES) return listOf(StreamKind.Unknown)
        val extension = reader.bytesAt(position, size.toInt())
        val typeAt = OBJECT_HEADER_BYTES.toInt()
        val propertiesBytes = STREAM_PROPERTIES_MIN_BYTES.toInt()
        return (GUID_BYTES..extension.size - propertiesBytes)
            .filter { offset -> extension.startsWithBytes(STREAM_PROPERTIES, offset) }
            .map { offset -> kindOf(extension.copyOfRange(offset + typeAt, offset + propertiesBytes)) }
    }

    /** The kind of an ASF stream type GUID. */
    private fun kindOf(type: ByteArray): StreamKind =
        when {
            type.contentEquals(VIDEO_MEDIA) -> StreamKind.Video
            type.contentEquals(AUDIO_MEDIA) -> StreamKind.Audio
            type.contentEquals(COMMAND_MEDIA) -> StreamKind.Neutral
            else -> StreamKind.Unknown
        }
}

/**
 * RealMedia: one Media Properties (`MDPR`) chunk per stream, before the `DATA` chunk, names the stream's MIME type,
 * and a video stream's type-specific data starts with a `VIDO` tag. Chunks are followed by their sizes, up to a fixed
 * count, and the stream list is complete only once `DATA` is reached.
 */
internal object RealMediaStreams {
    private const val CHUNK_HEADER_BYTES = 10L
    private const val CHUNK_SIZE_OFFSET = 4L
    private const val MEDIA_FIXED_BYTES = 30L
    private const val TYPE_DATA_LENGTH_BYTES = 4L
    private const val VIDEO_TAG_OFFSET = 4L
    private const val MAX_CHUNKS = 64
    private val DATA = ascii("DATA")
    private val MEDIA_PROPERTIES = ascii("MDPR")
    private val VIDEO_TAG = ascii("VIDO")

    /** What the RealMedia file in [reader] carries, from the media properties chunks before its data. */
    fun probe(reader: ScrubReader): CarriedStreams {
        val kinds = mutableListOf<StreamKind>()
        var position = 0L
        var chunks = 0
        var complete: Boolean? = null
        while (complete == null) {
            val present = position + CHUNK_HEADER_BYTES <= reader.size
            val size = if (present) reader.u32(position + CHUNK_SIZE_OFFSET) else 0L
            complete =
                when {
                    !present -> false
                    reader.matches(position, DATA) -> true
                    size < CHUNK_HEADER_BYTES || size > reader.size - position || chunks == MAX_CHUNKS -> false
                    else -> {
                        if (reader.matches(position, MEDIA_PROPERTIES)) kinds += mediaKind(reader, position, size)
                        position += size
                        chunks++
                        null
                    }
                }
        }
        return if (complete == true) carriedStreams(kinds) else CarriedStreams.Undetermined
    }

    /** The kind of the stream an `MDPR` chunk of [size] bytes at [start] describes, unknown when it does not fit. */
    private fun mediaKind(
        reader: ScrubReader,
        start: Long,
        size: Long,
    ): StreamKind {
        val end = start + size
        val nameAt = start + CHUNK_HEADER_BYTES + MEDIA_FIXED_BYTES
        val mimeAt = if (nameAt < end) nameAt + 1 + reader.u8(nameAt) else end
        val mimeLength = if (mimeAt < end) reader.u8(mimeAt) else 0
        val typeDataAt = mimeAt + 1 + mimeLength
        val fits = mimeAt < end && typeDataAt + TYPE_DATA_LENGTH_BYTES <= end
        val mime = if (fits) String(reader.bytesAt(mimeAt + 1, mimeLength), Charsets.US_ASCII) else ""
        val tagAt = typeDataAt + TYPE_DATA_LENGTH_BYTES + VIDEO_TAG_OFFSET
        val videoTag = fits && tagAt + VIDEO_TAG.size <= end && reader.matches(tagAt, VIDEO_TAG)
        val kind = mime.lowercase(Locale.ROOT).removePrefix("logical-")
        return when {
            !fits -> StreamKind.Unknown
            videoTag || kind.startsWith("video/") -> StreamKind.Video
            kind.startsWith("audio/") -> StreamKind.Audio
            kind == "fileinfo" -> StreamKind.Neutral
            else -> StreamKind.Unknown
        }
    }
}

private const val BYTE_MASK = 0xFFL

/** [text] as ASCII bytes. */
private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

/**
 * The 16 bytes ASF stores for the GUID [text]: its first three fields little-endian, as Windows lays a GUID out in
 * memory, and the last eight bytes in order. The ASF Header Object GUID 75B22630-668E-11CF-A6D9-00AA0062CE6C is stored
 * as `30 26 B2 75 8E 66 CF 11 A6 D9 00 AA 00 62 CE 6C`.
 */
internal fun asfGuid(text: String): ByteArray {
    val fields = text.split('-')
    val hex =
        fields[0].chunked(2).reversed() + fields[1].chunked(2).reversed() + fields[2].chunked(2).reversed() +
            (fields[3] + fields[4]).chunked(2)
    return ByteArray(hex.size) { hex[it].toInt(radix = 16).toByte() }
}

/** Whether this array holds [prefix] at [offset]. */
private fun ByteArray.startsWithBytes(
    prefix: ByteArray,
    offset: Int = 0,
): Boolean = offset + prefix.size <= size && prefix.indices.all { this[offset + it] == prefix[it] }

/** Whether the bytes at [position] equal [expected], false when the input ends first. */
private fun ScrubReader.matches(
    position: Long,
    expected: ByteArray,
): Boolean = position + expected.size <= size && bytesAt(position, expected.size).contentEquals(expected)

/** The [length] bytes at [position], which the caller has already bounded. */
private fun ScrubReader.bytesAt(
    position: Long,
    length: Int,
): ByteArray = ByteArray(length).also { store.read(position, it, 0, length) }

/** The [width] bytes at [position] as one little-endian number. */
private fun ScrubReader.littleEndian(
    position: Long,
    width: Int,
): Long {
    val bytes = bytesAt(position, width)
    return bytes.indices.reversed().fold(0L) { value, index ->
        (value shl Byte.SIZE_BITS) or (bytes[index].toLong() and BYTE_MASK)
    }
}
